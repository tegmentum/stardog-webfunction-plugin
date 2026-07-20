package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Unit tests for the {@link InMemoryFulltextRegistry} singleton and its
 * {@link FulltextIndex} carrier. Wave C — pure JVM, no Stardog /
 * wasmtime bootstrap needed. Every test resets the singleton so the
 * suite runs in any order.
 *
 * <p>Mirrors {@link TestSinkRegistry}'s shape — the two Wave A / Wave C
 * registries are structurally the same and deliberately covered with
 * the same test discipline.
 */
public class TestInMemoryFulltextRegistry {

    @Before
    public void setUp() {
        InMemoryFulltextRegistry.INSTANCE.reset();
    }

    @After
    public void tearDown() {
        InMemoryFulltextRegistry.INSTANCE.reset();
    }

    @Test
    public void registerThenLookupRoundTrips() {
        InMemoryFulltextRegistry.INSTANCE.register("alpha");
        InMemoryFulltextRegistry.INSTANCE.register("beta");

        assertThat(InMemoryFulltextRegistry.INSTANCE.indexNames())
                .containsExactly("alpha", "beta");
        assertThat(InMemoryFulltextRegistry.INSTANCE.index("alpha")).isPresent();
        assertThat(InMemoryFulltextRegistry.INSTANCE.index("alpha").get().name())
                .isEqualTo("alpha");
        assertThat(InMemoryFulltextRegistry.INSTANCE.contains("beta")).isTrue();
    }

    @Test
    public void unknownIndexReturnsEmpty() {
        InMemoryFulltextRegistry.INSTANCE.register("alpha");

        assertThat(InMemoryFulltextRegistry.INSTANCE.index("gamma")).isEmpty();
        assertThat(InMemoryFulltextRegistry.INSTANCE.contains("gamma")).isFalse();
    }

    @Test
    public void lookupOfNullReturnsEmpty() {
        assertThat(InMemoryFulltextRegistry.INSTANCE.index(null)).isEmpty();
        assertThat(InMemoryFulltextRegistry.INSTANCE.contains(null)).isFalse();
    }

    @Test
    public void duplicateRegisterThrows() {
        InMemoryFulltextRegistry.INSTANCE.register("alpha");
        final Throwable thrown = catchThrowable(() ->
                InMemoryFulltextRegistry.INSTANCE.register("alpha"));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha")
                .hasMessageContaining("already registered");
    }

    @Test
    public void blankOrNullNameRejected() {
        assertThat(catchThrowable(() -> InMemoryFulltextRegistry.INSTANCE.register(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> InMemoryFulltextRegistry.INSTANCE.register("")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> InMemoryFulltextRegistry.INSTANCE.register("   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void resetClearsEverything() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc1", List.of(new FulltextIndex.FieldPair("p", "v")), null);
        InMemoryFulltextRegistry.INSTANCE.reset();

        assertThat(InMemoryFulltextRegistry.INSTANCE.indexNames()).isEmpty();
        assertThat(InMemoryFulltextRegistry.INSTANCE.index("alpha")).isEmpty();
    }

    @Test
    public void singletonAccessorReturnsSameInstance() {
        assertThat(InMemoryFulltextRegistry.getInstance())
                .isSameAs(InMemoryFulltextRegistry.INSTANCE);
    }

    // ---- FulltextIndex: insert / delete / search --------------------

    @Test
    public void insertReplacesPriorDocumentByIdOverwriteSemantics() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc1",
                List.of(new FulltextIndex.FieldPair("p", "hello world")), null);
        idx.insertDocument("doc1",
                List.of(new FulltextIndex.FieldPair("p", "goodbye world")), null);

        assertThat(idx.documentCount()).isEqualTo(1);
        assertThat(idx.documents().get("doc1").fields().get(0).value())
                .isEqualTo("goodbye world");
    }

    @Test
    public void deleteReturnsTrueOnlyWhenPresent() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc1", List.of(new FulltextIndex.FieldPair("p", "v")), null);

        assertThat(idx.deleteDocument("doc1")).isTrue();
        assertThat(idx.deleteDocument("doc1")).isFalse();
        assertThat(idx.deleteDocument("never-existed")).isFalse();
    }

    @Test
    public void searchIsCaseInsensitiveSubstring() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc-uppercase",
                List.of(new FulltextIndex.FieldPair("p", "HELLO WORLD")), null);
        idx.insertDocument("doc-lowercase",
                List.of(new FulltextIndex.FieldPair("p", "hello world")), null);
        idx.insertDocument("doc-partial",
                List.of(new FulltextIndex.FieldPair("p", "Say hello.")), null);
        idx.insertDocument("doc-nomatch",
                List.of(new FulltextIndex.FieldPair("p", "unrelated")), null);

        final List<FulltextIndex.Hit> hits = idx.search("hello", null);

        assertThat(hits).extracting(FulltextIndex.Hit::id)
                .containsExactly("doc-lowercase", "doc-partial", "doc-uppercase");
        // All single-field docs — score = 1.0 each.
        assertThat(hits).extracting(FulltextIndex.Hit::score)
                .containsExactly(1.0, 1.0, 1.0);
    }

    @Test
    public void searchScoresByFieldMatchCount() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc-triple",
                List.of(
                        new FulltextIndex.FieldPair("p1", "cat"),
                        new FulltextIndex.FieldPair("p2", "cat"),
                        new FulltextIndex.FieldPair("p3", "cat")),
                null);
        idx.insertDocument("doc-single",
                List.of(new FulltextIndex.FieldPair("p", "cat")), null);

        final List<FulltextIndex.Hit> hits = idx.search("cat", null);
        assertThat(hits).hasSize(2);
        // Sorted by score DESC — doc-triple first with 3.0, doc-single with 1.0.
        assertThat(hits.get(0).id()).isEqualTo("doc-triple");
        assertThat(hits.get(0).score()).isEqualTo(3.0);
        assertThat(hits.get(1).id()).isEqualTo("doc-single");
        assertThat(hits.get(1).score()).isEqualTo(1.0);
    }

    @Test
    public void searchLimitTruncates() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        for (int i = 0; i < 5; i++) {
            idx.insertDocument("d" + i,
                    List.of(new FulltextIndex.FieldPair("p", "match")), null);
        }
        final List<FulltextIndex.Hit> hits = idx.search("match", 3);
        assertThat(hits).hasSize(3);
        assertThat(hits).extracting(FulltextIndex.Hit::id)
                .containsExactly("d0", "d1", "d2");
    }

    @Test
    public void searchEmptyQueryReturnsNoHits() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc1",
                List.of(new FulltextIndex.FieldPair("p", "hello")), null);

        assertThat(idx.search("", null)).isEmpty();
    }

    @Test
    public void searchNoMatchesReturnsEmpty() {
        final FulltextIndex idx = InMemoryFulltextRegistry.INSTANCE.register("alpha");
        idx.insertDocument("doc1",
                List.of(new FulltextIndex.FieldPair("p", "hello")), null);

        assertThat(idx.search("nothing-matches", null)).isEmpty();
    }

    // ---- config parsing (webfunctions.fulltext.indexes) -------------

    @Test
    public void configUnsetYieldsEmptyList() {
        System.clearProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES);
        assertThat(WebFunctionConfig.getFulltextIndexNames()).isEmpty();
    }

    @Test
    public void configSingleName() {
        System.setProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES, "alpha");
        try {
            assertThat(WebFunctionConfig.getFulltextIndexNames())
                    .containsExactly("alpha");
        } finally {
            System.clearProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES);
        }
    }

    @Test
    public void configMultipleNamesWithWhitespace() {
        System.setProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES,
                "alpha, beta ,  gamma");
        try {
            assertThat(WebFunctionConfig.getFulltextIndexNames())
                    .containsExactly("alpha", "beta", "gamma");
        } finally {
            System.clearProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES);
        }
    }

    @Test
    public void configEmptyPiecesDropped() {
        System.setProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES,
                ",alpha,,,beta,");
        try {
            assertThat(WebFunctionConfig.getFulltextIndexNames())
                    .containsExactly("alpha", "beta");
        } finally {
            System.clearProperty(WebFunctionConfig.PROP_FULLTEXT_INDEXES);
        }
    }
}
