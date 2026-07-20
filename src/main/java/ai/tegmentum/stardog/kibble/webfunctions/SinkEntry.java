package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * State carrier for a single named sink in the {@link SinkRegistry}.
 *
 * <p>Wave A — in-memory only. Holds two independent stores:
 *
 * <ul>
 *   <li>{@link #quads()} — a {@link ConcurrentLinkedDeque} of accumulated
 *       quad records ({@link ComponentVal} of WIT {@code types.quad}
 *       shape) written via {@code sink-callbacks::emit-quad(s)} and
 *       scanned via {@code sink-query-callbacks::scan-sink-quads}. Quads
 *       are stored in insertion order and never evicted at MVP.</li>
 *   <li>{@link #documents()} — a {@link ConcurrentHashMap} mapping the
 *       string-rendered form of the document's RDF-term key to the
 *       opaque content string, populated by
 *       {@code document-sink-callbacks::put-document}.</li>
 * </ul>
 *
 * <p>Thread-safety: both stores are lock-free concurrent collections.
 * The registry's outer map is a {@link ConcurrentHashMap} keyed by
 * sink name, so all three levels (registry map, entry's deque, entry's
 * map) tolerate concurrent readers and writers without external
 * synchronization. Chosen over a fully-synchronized shape so the scan
 * path (linear filter over the deque) doesn't block emit-side writers
 * — a scan may observe a partial write, which matches the memo's
 * "accumulator" semantics for the reference in-memory sink.
 */
public final class SinkEntry {

    private final String name;
    private final ConcurrentLinkedDeque<ComponentVal> quads = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, String> documents = new ConcurrentHashMap<>();

    SinkEntry(final String name) {
        this.name = name;
    }

    /** The sink's registered name — the routing key used by every
     *  sink-family host callback. */
    public String name() {
        return name;
    }

    /** Append a quad to the tail of the deque. Called by
     *  {@code sink-callbacks::emit-quad}. */
    public void addQuad(final ComponentVal quad) {
        quads.addLast(quad);
    }

    /** Append a batch of quads to the tail of the deque. Called by
     *  {@code sink-callbacks::emit-quads}. Returns the count added. */
    public int addQuads(final Collection<ComponentVal> batch) {
        int added = 0;
        for (final ComponentVal q : batch) {
            quads.addLast(q);
            added++;
        }
        return added;
    }

    /** Snapshot iterator over the currently accumulated quads. Backed
     *  by a lock-free deque — iteration is weakly consistent, matching
     *  {@link ConcurrentLinkedDeque}'s contract. */
    public Iterator<ComponentVal> iterateQuads() {
        return quads.iterator();
    }

    /** Number of quads currently in the deque. Snapshot only; may
     *  change concurrently with the reader. Test helper. */
    public int quadCount() {
        return quads.size();
    }

    /** Store or replace a document under its rendered-term key. */
    public void putDocument(final String renderedKey, final String content) {
        documents.put(renderedKey, content);
    }

    /** Fetch a document by rendered-term key. */
    public String getDocument(final String renderedKey) {
        return documents.get(renderedKey);
    }

    /** Remove a document by rendered-term key. Idempotent — missing
     *  key is not an error. Returns the previously mapped value or
     *  {@code null}. */
    public String removeDocument(final String renderedKey) {
        return documents.remove(renderedKey);
    }

    /** Read-only view of the document map. Test helper; not part of
     *  the WIT surface. */
    public Map<String, String> documents() {
        return java.util.Collections.unmodifiableMap(documents);
    }

    /** Raw quad deque handle. Test helper. */
    Collection<ComponentVal> quads() {
        return quads;
    }
}
