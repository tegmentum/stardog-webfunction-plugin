package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State carrier for a single named index in the
 * {@link InMemoryFulltextRegistry}.
 *
 * <p>Wave C — in-memory only. Holds a {@link ConcurrentHashMap} keyed
 * by document id, valued by {@link Document} carrying the indexed
 * {@code (predicate, literal-lex)} field pairs and optional BCP-47
 * language tag.
 *
 * <p><b>Insert-with-replace semantics.</b> {@link #insertDocument}
 * overwrites any prior document under the same id — mirrors the
 * Oxigraph reference impl.
 *
 * <p><b>Search algorithm.</b> Case-insensitive naive substring match
 * over every field value. Score = the count of fields whose value
 * (lowercased) contains the (lowercased) needle. Zero-score docs are
 * excluded. Ordering: score descending, then document id ascending for
 * stable output. This mirrors the Oxigraph reference impl's algorithm
 * exactly; production fulltext backends (Manticore, OpenSearch, BITES)
 * layer richer scoring behind the same WIT surface with no guest-side
 * change.
 *
 * <p><b>Thread-safety.</b> The document map is a lock-free
 * {@link ConcurrentHashMap}. {@link #search} takes a snapshot iterator
 * and may observe a partial concurrent insert — acceptable for a
 * reference in-memory backend; production impls own their own
 * consistency semantics against the backend store.
 */
public final class FulltextIndex {

    private final String name;
    private final ConcurrentHashMap<String, Document> docs = new ConcurrentHashMap<>();

    FulltextIndex(final String name) {
        this.name = name;
    }

    /** The index's registered name — the routing key used by every
     *  fulltext host callback. */
    public String name() {
        return name;
    }

    /**
     * Insert or replace a document. Called by
     * {@code fulltext-callbacks::insert-documents} per document in the
     * batch. Overwrite semantics: an insert of an existing id replaces
     * the prior document's fields + language.
     */
    public void insertDocument(final String id,
                               final List<FieldPair> fields,
                               final String lang) {
        docs.put(id, new Document(id, List.copyOf(fields), lang));
    }

    /**
     * Remove a document by id. Called by
     * {@code fulltext-callbacks::delete-documents} per id in the batch.
     * Returns {@code true} when a document existed and was removed;
     * {@code false} otherwise. The caller aggregates these into the
     * u32 count the interface returns.
     */
    public boolean deleteDocument(final String id) {
        return docs.remove(id) != null;
    }

    /**
     * Case-insensitive naive substring search over every document's
     * field values. Score = number of fields containing the needle.
     * Returns hits in score-descending, then id-ascending order.
     * A {@code null} or empty limit means "no cap" (unlike the
     * Oxigraph reference, which uses backend default — the in-memory
     * backend has no natural default, so unbounded matches the
     * behaviour a caller would get from a Manticore search with a
     * high limit).
     */
    public List<Hit> search(final String query, final Integer limit) {
        final String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        final List<Hit> hits = new ArrayList<>();
        for (final Document doc : docs.values()) {
            long matches = 0L;
            if (!needle.isEmpty()) {
                for (final FieldPair pair : doc.fields()) {
                    final String value = pair.value();
                    if (value != null
                            && value.toLowerCase(Locale.ROOT).contains(needle)) {
                        matches++;
                    }
                }
            }
            if (matches > 0L) {
                hits.add(new Hit(doc.id(), (double) matches));
            }
        }
        hits.sort(Comparator
                .comparingDouble(Hit::score).reversed()
                .thenComparing(Hit::id));
        if (limit != null && limit >= 0 && hits.size() > limit) {
            return hits.subList(0, limit);
        }
        return hits;
    }

    /** Test helper — read-only view of the document map. Not part of
     *  the WIT surface. */
    public Map<String, Document> documents() {
        return Collections.unmodifiableMap(docs);
    }

    /** Test helper — current document count. May race with
     *  concurrent insert/delete. */
    public int documentCount() {
        return docs.size();
    }

    /**
     * One indexed document. Snapshotted at insert time; the field list
     * and language are immutable after construction.
     */
    public static final class Document {
        private final String id;
        private final List<FieldPair> fields;
        private final String lang;

        Document(final String id, final List<FieldPair> fields, final String lang) {
            this.id = id;
            this.fields = fields;
            this.lang = lang;
        }

        public String id() { return id; }
        public List<FieldPair> fields() { return fields; }
        /** May be {@code null} when the guest passed {@code option::none}. */
        public String lang() { return lang; }
    }

    /**
     * One {@code (predicate, literal-lex)} pair from a document's
     * {@code fields} list. Predicate identifies the RDF field being
     * indexed; the literal is the indexable text.
     */
    public static final class FieldPair {
        private final String predicate;
        private final String value;

        public FieldPair(final String predicate, final String value) {
            this.predicate = predicate;
            this.value = value;
        }

        public String predicate() { return predicate; }
        public String value() { return value; }
    }

    /**
     * One search hit. {@code id} is the document id (which the
     * {@code fulltext-callbacks} caller marshals into the WIT
     * {@code fulltext-hit.subject} as a named-node term). {@code score}
     * is the count of matched fields, cast to a {@code double} for the
     * WIT {@code f64} field.
     */
    public static final class Hit {
        private final String id;
        private final double score;

        public Hit(final String id, final double score) {
            this.id = id;
            this.score = score;
        }

        public String id() { return id; }
        public double score() { return score; }
    }
}
