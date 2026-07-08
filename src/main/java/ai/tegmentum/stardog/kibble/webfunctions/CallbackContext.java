package ai.tegmentum.stardog.kibble.webfunctions;

import com.complexible.stardog.db.ConnectableConnection;
import com.complexible.stardog.plan.eval.ExecutionContext;
import com.complexible.stardog.plan.eval.ExecutionMonitor;
import com.complexible.stardog.query.DefaultQueryFactory;
import com.complexible.stardog.query.QueryFactory;
import com.complexible.stardog.query.ReadQuery;
import com.complexible.stardog.query.UpdateQuery;
import com.stardog.stark.Value;
import com.stardog.stark.query.SelectQueryResult;

import java.util.Map;

/**
 * Per-thread context for v0.3.0 host callback imports.
 *
 * <p>Two bind points:
 * <ul>
 *   <li>{@link #bind()} — no query connection. From {@link Call#evaluate}
 *       ({@code wf:call} in a BIND). The filter-function evaluator does
 *       not thread an {@link ExecutionContext} through to us, so the
 *       {@code execute-query} import is unreachable on this path.</li>
 *   <li>{@link #bind(ExecutionContext)} — full context. From
 *       {@link WebFunctionServiceOperator} ({@code SERVICE wf:call}).
 *       Sub-queries run against the outer query's connection via
 *       {@link DefaultQueryFactory#select}, inheriting its transaction,
 *       reasoning, and cancellation state.</li>
 * </ul>
 *
 * <p>Nested {@code wf:call} reuses the outer binding: {@link #bind}
 * / {@link #bind(ExecutionContext)} both no-op when a context is already
 * bound, and {@link #unbindIfOutermost} only clears the ThreadLocal on
 * the outermost frame.
 */
public final class CallbackContext {

    private static final ThreadLocal<CallbackContext> CURRENT = new ThreadLocal<>();

    private final ExecutionContext executionContext;
    private final int maxDepth;
    private final int maxRows;
    private int depth = 0;

    // v0.3.2 prepared-query handles. The Stardog QueryFactory produces a
    // ReadQuery bound to a specific connection + monitor; we re-parse per
    // call today, but Stardog's kernel-level plan cache short-circuits the
    // heavy lifting on repeat text. Storing the string, not the ReadQuery,
    // because ReadQuery.execute() is not repeatable — parameters+state carry.
    private final java.util.Map<Integer, String> prepared = new java.util.HashMap<>();
    private int nextHandle = 1;

    private CallbackContext(final ExecutionContext executionContext,
                            final int maxDepth,
                            final int maxRows) {
        this.executionContext = executionContext;
        this.maxDepth = maxDepth;
        this.maxRows = maxRows;
    }

    /** Bind without a query context — sub-queries via execute-query unavailable. */
    public static CallbackContext bind() {
        final CallbackContext existing = CURRENT.get();
        // Preserve nested (mid-callback) contexts so depth stays consistent;
        // always replace outermost so a stale-from-prior-query context
        // doesn't misroute sub-queries.
        if (existing != null && existing.depth > 0) return existing;
        final CallbackContext ctx = new CallbackContext(
                null,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows());
        CURRENT.set(ctx);
        return ctx;
    }

    /** Bind with the outer query's {@link ExecutionContext}. */
    public static CallbackContext bind(final ExecutionContext executionContext) {
        final CallbackContext existing = CURRENT.get();
        // Preserve nested (mid-callback) contexts so depth stays consistent;
        // always replace outermost so a stale-from-prior-query context
        // doesn't misroute sub-queries.
        if (existing != null && existing.depth > 0) return existing;
        final CallbackContext ctx = new CallbackContext(
                executionContext,
                WebFunctionConfig.callbackMaxDepth(),
                WebFunctionConfig.callbackMaxRows());
        CURRENT.set(ctx);
        return ctx;
    }

    public static void unbindIfOutermost(final CallbackContext ctx) {
        if (ctx.depth == 0 && CURRENT.get() == ctx) {
            CURRENT.remove();
        }
    }

    public static CallbackContext current() {
        return CURRENT.get();
    }

    public int enter() {
        if (depth >= maxDepth) {
            throw new RuntimeException(
                "wf callback depth limit exceeded: " + maxDepth
                + " (config: webfunctions.callback.max.depth)");
        }
        return ++depth;
    }

    public int exit() {
        return --depth;
    }

    public int depth() {
        return depth;
    }

    public int maxRows() {
        return maxRows;
    }

    /**
     * Run a SPARQL SELECT against the outer query's connection with the given
     * initial bindings pre-substituted. Uses {@link DefaultQueryFactory} so
     * the sub-query shares the connection's transaction, reasoning, and
     * cancellation manager with the outer query — the same "reuse the strategy"
     * semantics the RDF4J plugin gets from {@code strategy.precompile}.
     */
    public SelectQueryResult executeSelect(final String sparql,
                                           final Map<String, Value> initial) {
        if (executionContext == null) {
            throw new IllegalStateException(
                "wf callback: execute-query needs a bound ExecutionContext — "
                + "only SERVICE wf:call binds one. Filter-function wf:call "
                + "(BIND) cannot access the outer connection through Stardog's "
                + "filter-function API.");
        }
        final ConnectableConnection conn = executionContext.getConnection();
        final ExecutionMonitor monitor = executionContext.getMonitor();
        final QueryFactory qf = new DefaultQueryFactory(conn, () -> monitor);
        final ReadQuery<SelectQueryResult> q;
        try {
            q = qf.select("", sparql, null);
        } catch (com.stardog.stark.query.MalformedQuery mq) {
            throw new RuntimeException(mq);
        }
        for (Map.Entry<String, Value> e : initial.entrySet()) {
            q.parameter(e.getKey(), e.getValue());
        }
        return q.execute();
    }

    /** v0.3.2 prepare-query — store SPARQL text; Stardog's plan cache
     *  short-circuits recompilation on repeat text. */
    public int prepare(final String sparql) {
        final int h = nextHandle++;
        prepared.put(h, sparql);
        return h;
    }

    /**
     * v0.3.3 follow-predicate — {@code SELECT ?o WHERE { $s $p ?o }} with
     * the subject and predicate substituted. Stardog's kernel plan cache
     * short-circuits recompilation on repeated invocations of this text.
     * Not as fast as direct triple-source access (RDF4J + Jena go there),
     * but this preserves transaction semantics via the outer connection.
     */
    public java.util.List<Value> followPredicate(final Value subject, final Value predicate) {
        final Map<String, Value> bindings = new java.util.LinkedHashMap<>();
        bindings.put("s", subject);
        bindings.put("p", predicate);
        final java.util.List<Value> out = new java.util.ArrayList<>();
        try (SelectQueryResult rs = executeSelect(
                "SELECT ?o WHERE { ?s ?p ?o }", bindings)) {
            while (rs.hasNext()) {
                final com.stardog.stark.query.BindingSet bs = rs.next();
                final Value v = bs.get("o");
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    /** v0.3.2 run-prepared — evaluate a prepared handle with fresh bindings. */
    public SelectQueryResult runPrepared(final int handle, final Map<String, Value> initial) {
        final String sparql = prepared.get(handle);
        if (sparql == null) {
            throw new RuntimeException("wf callback: unknown prepared handle " + handle);
        }
        return executeSelect(sparql, initial);
    }

    /**
     * v0.3.1 execute-update — SPARQL 1.1 UPDATE against the outer query's
     * connection. Runs through the same {@link DefaultQueryFactory}, so
     * initial-binding substitution and the outer transaction apply exactly
     * as with {@link #executeSelect}.
     */
    public void executeUpdate(final String sparql, final Map<String, Value> initial) {
        if (executionContext == null) {
            throw new IllegalStateException(
                "wf callback: execute-update needs a bound ExecutionContext");
        }
        final ConnectableConnection conn = executionContext.getConnection();
        final ExecutionMonitor monitor = executionContext.getMonitor();
        final QueryFactory qf = new DefaultQueryFactory(conn, () -> monitor);
        final UpdateQuery upd;
        try {
            upd = qf.update("", sparql, null);
        } catch (com.stardog.stark.query.MalformedQuery mq) {
            throw new RuntimeException(mq);
        }
        for (Map.Entry<String, Value> e : initial.entrySet()) {
            upd.parameter(e.getKey(), e.getValue());
        }
        upd.execute();
    }
}
