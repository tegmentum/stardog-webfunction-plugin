package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * WIT {@code quad} record -&gt; RDF4J {@link Statement} rollup for the
 * sink-SPARQL evaluator.
 *
 * <p>The sink deque holds {@link ComponentVal} records of shape
 * {@code {subject: term, predicate: term, object: term, graph: option<iri>}}
 * — the same shape {@code sink-callbacks::emit-quad} accepts. Loading
 * them into a per-invocation
 * {@link org.eclipse.rdf4j.sail.memory.MemoryStore} requires converting
 * each record into a rdf4j {@link Statement} via the store's own
 * {@link ValueFactory}, then handing it to
 * {@link org.eclipse.rdf4j.sail.SailConnection#addStatement}.
 *
 * <p>Term-level conversion delegates to {@link SinkValueMarshaller};
 * this class is a thin composition helper. Graph position uses
 * {@code null} for the default graph so
 * {@code addStatement(subj, pred, obj)} (varargs form) dispatches without
 * spelunking rdf4j's context sentinel.
 */
public final class SinkStatementMarshaller {

    private SinkStatementMarshaller() {}

    /**
     * Marshal one WIT quad record into a rdf4j {@link Statement} in the
     * factory's naming.
     *
     * @throws IllegalArgumentException on shape mismatch (missing
     *         required field, non-Resource subject, non-IRI predicate,
     *         etc). Callers translate to
     *         {@code sink-query-error::backend-error} with the message.
     */
    public static Statement fromWitQuad(final ComponentVal quadRecord, final ValueFactory vf) {
        if (quadRecord == null) {
            throw new IllegalArgumentException(
                    "SinkStatementMarshaller.fromWitQuad: null quad record");
        }
        final Map<String, ComponentVal> fields = quadRecord.asRecord();
        final Resource subject = SinkValueMarshaller.fromWitTermResource(
                required(fields, "subject"), vf);
        final IRI predicate = SinkValueMarshaller.fromWitTermIri(
                required(fields, "predicate"), vf);
        final Value object = SinkValueMarshaller.fromWitTerm(
                required(fields, "object"), vf);
        final Resource context = graphAsContext(fields.get("graph"), vf);
        if (context == null) {
            return vf.createStatement(subject, predicate, object);
        }
        return vf.createStatement(subject, predicate, object, context);
    }

    /**
     * Load an iterator of WIT quads into the given
     * {@link org.eclipse.rdf4j.sail.SailConnection} inside its active
     * transaction. Caller owns transaction lifecycle
     * ({@code begin} / {@code commit}) — see {@link SinkSparqlEngine}
     * for the per-invocation shape.
     *
     * @return the number of statements added.
     */
    public static int loadInto(final org.eclipse.rdf4j.sail.SailConnection conn,
                                final ValueFactory vf,
                                final Iterator<ComponentVal> quads) {
        int loaded = 0;
        while (quads.hasNext()) {
            final Statement s = fromWitQuad(quads.next(), vf);
            if (s.getContext() == null) {
                conn.addStatement(s.getSubject(), s.getPredicate(), s.getObject());
            } else {
                conn.addStatement(s.getSubject(), s.getPredicate(), s.getObject(),
                        s.getContext());
            }
            loaded++;
        }
        return loaded;
    }

    private static ComponentVal required(final Map<String, ComponentVal> fields,
                                         final String name) {
        final ComponentVal v = fields.get(name);
        if (v == null) {
            throw new IllegalArgumentException(
                    "SinkStatementMarshaller: WIT quad record missing '"
                            + name + "' field");
        }
        return v;
    }

    private static Resource graphAsContext(final ComponentVal graphField,
                                           final ValueFactory vf) {
        if (graphField == null) return null;
        final Optional<ComponentVal> some = graphField.asSome();
        if (some.isEmpty()) return null;
        return vf.createIRI(some.get().asString());
    }
}
