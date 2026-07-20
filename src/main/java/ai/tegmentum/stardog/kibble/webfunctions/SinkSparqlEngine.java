package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * SPARQL SELECT evaluator backing
 * {@code sink-query-callbacks::execute-sink-select}.
 *
 * <p>Per-invocation lifecycle. Each call:
 * <ol>
 *   <li>Parses the SPARQL text via {@link SPARQLParser}, rejecting
 *       anything that isn't a {@link ParsedTupleQuery} (SELECT-shape
 *       only per the WIT interface doc — ASK / CONSTRUCT / DESCRIBE are
 *       out of MVP scope).</li>
 *   <li>Spins a fresh {@link MemoryStore}, initialises it, opens a
 *       {@link SailConnection}.</li>
 *   <li>Loads the sink's accumulated {@link ComponentVal} quads into
 *       the store via {@link SinkStatementMarshaller#loadInto} inside
 *       a single write transaction.</li>
 *   <li>Evaluates the parsed tuple-expression against the loaded store,
 *       iterating the {@link CloseableIteration} of
 *       {@link BindingSet}s and marshalling each solution into WIT
 *       {@code binding} records via
 *       {@link SinkBindingMarshaller#toWitBindings}.</li>
 *   <li>Closes the iterator, closes the connection, shuts the store
 *       down.</li>
 * </ol>
 *
 * <p>Not a perf optimization opportunity for MVP: the plan deliberately
 * pays the O(quads) load cost on every {@code execute-sink-select}
 * invocation. The sink's {@link SinkEntry#quads()} deque is the shared
 * source of truth for both the read path (this engine) and the write
 * path ({@code sink-callbacks::emit-quad}); no connection or store is
 * cached across invocations so a Sail's stale-snapshot semantics never
 * surface.
 *
 * <p>Thread-safety: reentrant / lock-free — every invocation is its
 * own MemoryStore. Concurrent invocations against the same
 * {@link SinkEntry} observe the entry's weakly-consistent deque
 * iterator, matching {@link java.util.concurrent.ConcurrentLinkedDeque}'s
 * contract (a read may see a partial concurrent write, no exception).
 */
public final class SinkSparqlEngine {

    /** Singleton — no per-instance state; the class is a pure invocation
     *  entry point. {@link HostCallbacks#sinkQueryExecuteSelect} calls
     *  {@link #INSTANCE} without an explicit constructor for symmetry
     *  with {@link SinkRegistry#INSTANCE}. */
    public static final SinkSparqlEngine INSTANCE = new SinkSparqlEngine();

    private SinkSparqlEngine() {}

    /**
     * Run a SPARQL SELECT against the sink's accumulated quads.
     * Returns a flat list of WIT {@code binding} records in row-major
     * order across all solutions, matching the WIT contract's
     * split-on-repeated-variable-identity boundary marking.
     *
     * @throws SyntaxError               on SPARQL parse failure or
     *                                   non-SELECT shape. Callers map to
     *                                   {@code sink-query-error::syntax-error}.
     * @throws BackendError              on Sail startup / evaluation
     *                                   failure or WIT quad shape
     *                                   mismatch during load. Callers
     *                                   map to
     *                                   {@code sink-query-error::backend-error}.
     */
    public List<ComponentVal> evaluate(final SinkEntry sink, final String sparql) {
        if (sink == null) {
            throw new IllegalArgumentException(
                    "SinkSparqlEngine.evaluate: null sink entry");
        }
        if (sparql == null) {
            throw new SyntaxError("SPARQL text is null");
        }

        final ParsedTupleQuery tupleQuery = parseSelect(sparql);

        final MemoryStore store = new MemoryStore();
        try {
            store.init();
        } catch (SailException e) {
            throw new BackendError(
                    "sink-sparql: MemoryStore init failed: " + rootMessage(e), e);
        }

        try {
            final ValueFactory vf = store.getValueFactory();
            try (SailConnection conn = store.getConnection()) {
                loadQuads(conn, vf, sink);
                return runQuery(conn, tupleQuery);
            } catch (SailException | QueryEvaluationException e) {
                throw new BackendError(
                        "sink-sparql: evaluation failed: " + rootMessage(e), e);
            } catch (IllegalArgumentException e) {
                // Marshaller shape-mismatch — a quad in the sink deque
                // is malformed. Backend, not syntax: the SPARQL parsed
                // fine, the sink's data is what's wrong.
                throw new BackendError(
                        "sink-sparql: quad marshalling failed while loading sink '"
                                + sink.name() + "': " + e.getMessage(), e);
            }
        } finally {
            try {
                store.shutDown();
            } catch (SailException ignored) {
                // Best-effort shutdown — the primary exception already
                // propagated, don't clobber it with the close failure.
            }
        }
    }

    private static ParsedTupleQuery parseSelect(final String sparql) {
        final SPARQLParser parser = new SPARQLParser();
        final ParsedQuery parsed;
        try {
            parsed = parser.parseQuery(sparql, null);
        } catch (MalformedQueryException e) {
            throw new SyntaxError(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
        if (!(parsed instanceof ParsedTupleQuery)) {
            // WIT contract: execute-sink-select only supports SELECT.
            // Guests can express ASK via LIMIT 1; CONSTRUCT / DESCRIBE
            // over a sink's demoted state is out of MVP scope.
            throw new SyntaxError(
                    "execute-sink-select accepts only SELECT queries; got "
                            + parsed.getClass().getSimpleName()
                            + ". ASK is expressible as SELECT ... LIMIT 1; "
                            + "CONSTRUCT / DESCRIBE against a sink are out of scope.");
        }
        return (ParsedTupleQuery) parsed;
    }

    private static void loadQuads(final SailConnection conn,
                                  final ValueFactory vf,
                                  final SinkEntry sink) {
        conn.begin();
        try {
            SinkStatementMarshaller.loadInto(conn, vf, sink.iterateQuads());
            conn.commit();
        } catch (RuntimeException e) {
            try {
                conn.rollback();
            } catch (SailException ignored) {
                // rollback failure secondary to load failure.
            }
            throw e;
        }
    }

    private static List<ComponentVal> runQuery(final SailConnection conn,
                                               final ParsedTupleQuery tupleQuery) {
        final List<ComponentVal> flat = new ArrayList<>();
        try (CloseableIteration<? extends BindingSet> iter =
                     conn.evaluate(tupleQuery.getTupleExpr(),
                             tupleQuery.getDataset(),
                             EmptyBindingSet.getInstance(),
                             false)) {
            while (iter.hasNext()) {
                flat.addAll(SinkBindingMarshaller.toWitBindings(iter.next()));
            }
        }
        return flat;
    }

    private static String rootMessage(final Throwable t) {
        Throwable cur = t;
        String last = cur.getMessage();
        int hops = 0;
        while (cur.getCause() != null && hops++ < 8) {
            cur = cur.getCause();
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                last = cur.getMessage();
            }
        }
        return last == null ? t.toString() : last;
    }

    /**
     * Thrown when the SPARQL text fails to parse or is not a SELECT.
     * {@link HostCallbacks#sinkQueryExecuteSelect} translates this into
     * the WIT {@code sink-query-error::syntax-error} arm.
     */
    public static final class SyntaxError extends RuntimeException {
        public SyntaxError(final String message) {
            super(message);
        }
        public SyntaxError(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown on Sail startup / evaluation failure, or on a
     * malformed WIT quad landing in the sink deque. Callers translate
     * to WIT {@code sink-query-error::backend-error}.
     */
    public static final class BackendError extends RuntimeException {
        public BackendError(final String message) {
            super(message);
        }
        public BackendError(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
