package ai.tegmentum.stardog.kibble.webfunctions.compose;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave B C11 — verify {@code sha256://<hex>} URLs resolve through the
 * artifact store, so a composed CID rides the standard
 * {@link URL#openConnection()} + stream extraction path
 * {@link ai.tegmentum.stardog.kibble.webfunctions.StardogWasmInstance#getWasm(URL)}
 * uses for other extension URLs.
 *
 * <p>Unit-level integration: hand a byte payload to
 * {@link ComposedArtifactStore}, register the store globally, open the
 * resulting CID as a URL, and confirm the read-back matches.
 */
public class TestSha256UrlLoader {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void openConnectionReadsPersistedBytes() throws Exception {
        Sha256ArtifactUrlHandler.install();
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        Sha256ArtifactUrlHandler.setStore(store);

        final byte[] payload = "wasm-bytes-payload".getBytes();
        final String cid = store.persist(payload);
        final String hex = cid.substring("sha256:".length());
        final URL url = new URL("sha256://" + hex);
        try (InputStream in = url.openConnection().getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[128];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            assertThat(out.toByteArray()).isEqualTo(payload);
        }
    }

    @Test
    public void openConnectionThrowsForMissingCid() throws Exception {
        Sha256ArtifactUrlHandler.install();
        final Path root = tmp.newFolder().toPath();
        final ComposedArtifactStore store = new ComposedArtifactStore(root);
        Sha256ArtifactUrlHandler.setStore(store);

        final String bogus = "sha256://" + "0".repeat(64);
        final URL url = new URL(bogus);
        assertThatThrownBy(() -> url.openConnection().getInputStream())
                .hasMessageContaining("no artifact");
    }
}
