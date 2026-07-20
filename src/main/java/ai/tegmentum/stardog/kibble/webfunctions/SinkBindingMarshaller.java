package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RDF4J {@link BindingSet} -&gt; WIT {@code binding} record encoder for
 * the sink-SPARQL evaluator.
 *
 * <p>WIT {@code binding} (from {@code types.wit}):
 * <pre>
 *   record binding {
 *       variable: string,
 *       value: term,
 *   }
 * </pre>
 *
 * <p>{@code sink-query-callbacks::execute-sink-select} returns
 * {@code list<binding>} — deliberately a flat list rather than a list of
 * rows. Repeated variable identity marks the row boundary; see the WIT
 * interface doc's "guests that need per-row grouping split on repeated
 * variable identity". Deleted-from-solution variables (RDF4J omits them
 * from the {@link BindingSet}) are simply absent from the emitted list,
 * matching {@code SELECT}'s under-defined-variable convention.
 */
public final class SinkBindingMarshaller {

    private SinkBindingMarshaller() {}

    /**
     * Encode one rdf4j {@link BindingSet} as an ordered list of WIT
     * {@code binding} records. Variable iteration order follows the
     * BindingSet's own {@link Iterable} order — {@code MapBindingSet}
     * (rdf4j's evaluator default) preserves insertion order, which
     * matches the SELECT projection order for the common case.
     */
    public static List<ComponentVal> toWitBindings(final BindingSet bs) {
        final List<ComponentVal> out = new ArrayList<>(bs.size());
        for (final Binding b : bs) {
            final Map<String, ComponentVal> fields = new LinkedHashMap<>();
            fields.put("variable", ComponentVal.string(b.getName()));
            fields.put("value", SinkValueMarshaller.toWitTerm(b.getValue()));
            out.add(ComponentVal.record(fields));
        }
        return out;
    }

    /**
     * Convenience: flatten a solution stream into one list of
     * {@code binding} records in row-major order. Callers hand
     * {@link org.eclipse.rdf4j.common.iteration.CloseableIteration}
     * closure discipline; this method only reads the iterator.
     */
    public static List<ComponentVal> flattenSolutions(final Iterable<? extends BindingSet> solutions) {
        final List<ComponentVal> out = new ArrayList<>();
        for (final BindingSet bs : solutions) {
            out.addAll(toWitBindings(bs));
        }
        return out;
    }
}
