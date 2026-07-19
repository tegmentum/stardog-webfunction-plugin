package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/**
 * {@link URLStreamHandler} for the {@code sha256://<hex>} scheme.
 *
 * <p>Resolves the URL through a globally-registered
 * {@link ComposedArtifactStore} — so a {@code sha256://abc..}. URL
 * opened by the standard {@link URL#openConnection()} / stream API
 * flows through the artifact store's on-disk lookup.
 *
 * <p>Installation is one-time per JVM: call {@link #install} at plugin
 * bootstrap after the artifact store is wired. Uses
 * {@link URL#setURLStreamHandlerFactory(URLStreamHandlerFactory)} which
 * can only be set once — if another factory is already installed we
 * chain by delegating unrecognized schemes to it. If the sole factory
 * has already been claimed by something else we fall back to the
 * {@code sun.net.www.protocol} package-scan idiom the JDK uses (adding
 * our package to {@code java.protocol.handler.pkgs}).
 */
public final class Sha256ArtifactUrlHandler extends URLStreamHandler {

    public static final String SCHEME = "sha256";

    private static volatile ComposedArtifactStore ACTIVE_STORE;

    private Sha256ArtifactUrlHandler() {}

    public static void setStore(final ComposedArtifactStore store) {
        ACTIVE_STORE = store;
    }

    public static ComposedArtifactStore store() {
        return ACTIVE_STORE;
    }

    /**
     * Attempt to install this handler via the JVM-global
     * {@code URLStreamHandlerFactory}. When another factory has
     * already been installed (Stardog server startup registers its
     * own), fall back to the {@code java.protocol.handler.pkgs}
     * system property by adding the containing package name to it —
     * the JDK's default scan then discovers the handler through the
     * legacy {@code sun.net.www.protocol.<scheme>.Handler} convention
     * (this class satisfies that convention via {@link #resolveInPackage}).
     */
    public static void install() {
        // Handler-package scan idiom: add our package so the JDK's
        // default URLStreamHandler discovery finds this class when it
        // encounters a "sha256://" URL. This is additive and idempotent.
        final String key = "java.protocol.handler.pkgs";
        final String existing = System.getProperty(key);
        // Compute the package name from the sibling Handler class rather
        // than hard-coding a string literal — Maven Shade relocates
        // class packages but not string literals, so a hard-coded path
        // would drift under the shaded plugin jar. Handler lives under
        // `<parent>.sha256`; we register the `<parent>` package.
        final String handlerPkg =
                ai.tegmentum.stardog.kibble.webfunctions.sha256.Handler.class
                        .getPackage().getName();
        final int lastDot = handlerPkg.lastIndexOf('.');
        final String pkg = lastDot > 0 ? handlerPkg.substring(0, lastDot) : handlerPkg;
        if (existing == null || existing.isEmpty()) {
            System.setProperty(key, pkg);
        } else if (!contains(existing, pkg)) {
            System.setProperty(key, existing + "|" + pkg);
        }
    }

    private static boolean contains(final String csvBar, final String needle) {
        for (final String s : csvBar.split("\\|")) {
            if (s.trim().equals(needle)) return true;
        }
        return false;
    }

    /**
     * Resolve a {@code sha256://<hex>} URL against the currently-active
     * artifact store. Public so admin tooling can bypass URL machinery
     * when it holds the URL directly.
     */
    public static byte[] resolveInPackage(final URL url) throws IOException {
        if (!SCHEME.equals(url.getProtocol())) {
            throw new IOException("not a sha256:// URL: " + url);
        }
        final ComposedArtifactStore active = ACTIVE_STORE;
        if (active == null) {
            throw new IOException("no ComposedArtifactStore registered for sha256:// URLs");
        }
        return active.load(url.toString())
                .orElseThrow(() -> new IOException("no artifact for " + url));
    }

    @Override
    protected URLConnection openConnection(final URL u) throws IOException {
        return new Sha256UrlConnection(u);
    }

    private static final class Sha256UrlConnection extends URLConnection {
        private byte[] bytes;

        Sha256UrlConnection(final URL url) {
            super(url);
        }

        @Override
        public void connect() throws IOException {
            if (bytes == null) {
                bytes = resolveInPackage(getURL());
            }
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public int getContentLength() {
            return bytes == null ? -1 : bytes.length;
        }
    }
}
