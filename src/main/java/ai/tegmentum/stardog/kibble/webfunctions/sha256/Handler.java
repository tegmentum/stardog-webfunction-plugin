package ai.tegmentum.stardog.kibble.webfunctions.sha256;

import ai.tegmentum.stardog.kibble.webfunctions.compose.Sha256ArtifactUrlHandler;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * URL protocol handler for the {@code sha256://<hex>} content-address
 * scheme composed wasm artifacts live under.
 *
 * <p>Discovered by the JDK's default {@code URLStreamHandler} lookup
 * via the {@code java.protocol.handler.pkgs} system property — the
 * loader adds this class's parent package to that property at plugin
 * bootstrap time (see {@link Sha256ArtifactUrlHandler#install()}), and
 * the JDK's default resolver then locates
 * {@code <pkg>.sha256.Handler} when it encounters a {@code sha256://}
 * URL.
 *
 * <p>This class is a thin delegator to {@link Sha256ArtifactUrlHandler}
 * — the discovery machinery is the JDK convention on class name /
 * package location; the resolution logic lives in the compose
 * subpackage next to the artifact store.
 */
public final class Handler extends URLStreamHandler {

    @Override
    protected URLConnection openConnection(final URL u) throws IOException {
        return new SafeConnection(u);
    }

    /**
     * Delegates {@link URLConnection#connect} + {@link #getInputStream}
     * to {@link Sha256ArtifactUrlHandler#resolveInPackage(URL)}. The
     * inner class replaces the {@code final URLConnection} returned by
     * {@link Sha256ArtifactUrlHandler}'s own {@code openConnection}
     * — same shape, split-package-safe.
     */
    private static final class SafeConnection extends URLConnection {

        private byte[] bytes;

        SafeConnection(final URL url) {
            super(url);
        }

        @Override
        public void connect() throws IOException {
            if (bytes == null) {
                bytes = Sha256ArtifactUrlHandler.resolveInPackage(getURL());
            }
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            connect();
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public int getContentLength() {
            return bytes == null ? -1 : bytes.length;
        }
    }
}
