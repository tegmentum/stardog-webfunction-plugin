package ai.tegmentum.stardog.kibble.webfunctions.compose;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ComposedArtifactStore} — content-addressed
 * persistence + artifact URL computation + URL-scheme normalization.
 */
public class TestComposedArtifactStore {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void urlForKnownVector() {
        // "" → e3b0c442... — the well-known SHA-256 of an empty string.
        assertThat(ComposedArtifactStore.urlFor(new byte[0]))
                .isEqualTo("sha256://e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    public void persistPlacesFileAtHexName() throws Exception {
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        final byte[] payload = "hello".getBytes();
        final String artifactUrl = store.persist(payload);
        assertThat(artifactUrl).startsWith("sha256://");
        final String hex = artifactUrl.substring("sha256://".length());
        assertThat(Files.exists(root.resolve(hex + ".wasm"))).isTrue();
        assertThat(Files.readAllBytes(root.resolve(hex + ".wasm"))).isEqualTo(payload);
    }

    @Test
    public void persistIsIdempotent() throws Exception {
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        final byte[] payload = "same-content".getBytes();
        final String first = store.persist(payload);
        final String second = store.persist(payload);
        assertThat(first).isEqualTo(second);
        // Only the final file plus no stray .part temp files should exist.
        try (var stream = Files.list(root)) {
            final long fileCount = stream.count();
            assertThat(fileCount).isEqualTo(1L);
        }
    }

    @Test
    public void loadRoundTripsCanonicalUrl() throws Exception {
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        final byte[] payload = "load-me".getBytes();
        final String artifactUrl = store.persist(payload);
        assertThat(store.load(artifactUrl)).contains(payload);
    }

    @Test
    public void loadAcceptsBareHashPrefixVariant() throws Exception {
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        final byte[] payload = "url-scheme".getBytes();
        final String artifactUrl = store.persist(payload);
        // Strip the URL scheme (sha256://) and re-attach the bare
        // hash-pair prefix (sha256:) to exercise the load() surface
        // that accepts both forms.
        final String bareForm = "sha256:" + artifactUrl.substring("sha256://".length());
        assertThat(store.load(bareForm)).contains(payload);
    }

    @Test
    public void loadReturnsEmptyForMissing() throws Exception {
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        final String bogus = "sha256://" + "0".repeat(64);
        assertThat(store.load(bogus)).isEqualTo(Optional.empty());
    }

    @Test
    public void loadReturnsEmptyForMalformedInput() throws Exception {
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        assertThat(store.load("sha256://not-hex")).isEqualTo(Optional.empty());
        assertThat(store.load("ipfs://Qm...")).isEqualTo(Optional.empty());
        assertThat(store.load(null)).isEqualTo(Optional.empty());
    }

    @Test
    public void normalizeRejectsShortHex() {
        assertThat(ComposedArtifactStore.normalize("sha256://abc")).isNull();
    }
}
