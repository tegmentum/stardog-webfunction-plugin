package ai.tegmentum.stardog.kibble.webfunctions;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sidecar manifest loader per {@code capability-implementation.md} §3
 * / strategy memo §6.
 *
 * <p>Fetches {@code foo.wasm.toml} sitting next to {@code foo.wasm} at
 * the same URL, parses it as TOML via {@link org.tomlj.Toml}, and
 * produces an {@link ExtensionManifest}. Reuses the same
 * {@link URLConnection} machinery {@code StardogWasmInstance} already
 * uses for the {@code .wasm} bytes, so the {@code ipfs://} / {@code ipns://}
 * URL-stream handlers already registered by the plugin serve the sidecar
 * too.
 *
 * <p>Failure modes are collapsed onto
 * {@link WfCapabilityError.ManifestMalformed} so callers dispatch on a
 * single error type:
 * <ul>
 *   <li>Missing sidecar (404 / FileNotFoundException / IOException) →
 *       {@code ManifestMalformed} with {@code "manifest not found at <url>"}.</li>
 *   <li>Malformed TOML (parse errors, structural mismatches) →
 *       {@code ManifestMalformed} with the parse-error details, including
 *       source position when tomlj provides one.</li>
 *   <li>I/O read failure → {@code ManifestMalformed} with the underlying
 *       message.</li>
 * </ul>
 *
 * <p>Non-goals in Phase 1 per strategy memo §6: capability composition,
 * delegation, temporal scoping, conditional grants. The parser reads
 * the sections it recognizes and ignores unknown top-level keys; a
 * forward-compat mismatch does not fail the load.
 */
public final class ExtensionManifestLoader {

    /**
     * Convention — the sidecar sits at the wasm URL plus {@code .toml}.
     * Matches the {@code foo.wasm} / {@code foo.wasm.toml} pair the
     * implementation memo names.
     */
    public static final String SIDECAR_SUFFIX = ".toml";

    private ExtensionManifestLoader() {}

    /**
     * Load and parse the sidecar manifest for {@code wasmUrl}. Returns
     * the parsed shape on success; throws
     * {@link WfCapabilityError.ManifestMalformed} on any failure path.
     */
    public static ExtensionManifest load(final URL wasmUrl) {
        Objects.requireNonNull(wasmUrl, "wasmUrl");
        final URL sidecar;
        try {
            sidecar = sidecarUrlFor(wasmUrl);
        } catch (MalformedURLException e) {
            throw new WfCapabilityError.ManifestMalformed(
                    wasmUrl.toString(),
                    "cannot compute sidecar URL: " + e.getMessage());
        }

        final byte[] bytes;
        try {
            bytes = fetch(sidecar);
        } catch (IOException e) {
            throw new WfCapabilityError.ManifestMalformed(
                    sidecar.toString(),
                    "manifest not found at " + sidecar + ": " + e.getMessage());
        }

        final TomlParseResult toml;
        try {
            toml = Toml.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new WfCapabilityError.ManifestMalformed(
                    sidecar.toString(),
                    "toml parse threw: " + e.getMessage());
        }

        if (toml.hasErrors()) {
            final StringBuilder sb = new StringBuilder();
            for (final TomlParseError err : toml.errors()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(err.getMessage());
                if (err.position() != null) {
                    sb.append(" (line ").append(err.position().line())
                      .append(", column ").append(err.position().column())
                      .append(")");
                }
            }
            throw new WfCapabilityError.ManifestMalformed(sidecar.toString(), sb.toString());
        }

        try {
            return parseManifest(toml);
        } catch (RuntimeException e) {
            throw new WfCapabilityError.ManifestMalformed(
                    sidecar.toString(),
                    "structural mismatch: " + e.getMessage());
        }
    }

    /**
     * The sidecar sits at {@code <wasmUrl>.toml}. Preserves the URL
     * protocol so the plugin's registered handlers (ipfs://, ipns://)
     * serve it identically to how they serve the wasm.
     */
    static URL sidecarUrlFor(final URL wasmUrl) throws MalformedURLException {
        return new URL(wasmUrl.toString() + SIDECAR_SUFFIX);
    }

    private static byte[] fetch(final URL url) throws IOException {
        final URLConnection conn = url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.connect();
        try (InputStream in = conn.getInputStream();
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Parse the sections named in strategy memo §6:
     * <ul>
     *   <li>{@code [package]} — name, version, signer</li>
     *   <li>{@code [capabilities.required]} — required interfaces</li>
     *   <li>{@code [capabilities.optional]} — optional interfaces</li>
     *   <li>{@code [capabilities.http-callbacks]} — {@code allow_hosts}</li>
     *   <li>{@code [capabilities.graph-callbacks]} — {@code methods}
     *       (method-level policy)</li>
     * </ul>
     *
     * <p>Also reads an optional {@code [capabilities] model} scalar
     * to honor implementation memo §13's per-extension declaration
     * ({@code invoker-subject} | {@code ambient}). Defaults to
     * {@link CapabilityModel#AMBIENT} for back-compat.
     */
    static ExtensionManifest parseManifest(final TomlTable toml) {
        // [package] — soft: any missing field becomes empty string.
        final TomlTable pkg = toml.getTableOrEmpty("package");
        final String name    = pkg.getString("name",    () -> "");
        final String version = pkg.getString("version", () -> "");
        final String signer  = pkg.getString("signer",  () -> "");

        // [capabilities.*] — the capability declarations.
        final TomlTable required = toml.getTableOrEmpty("capabilities.required");
        final TomlTable optional = toml.getTableOrEmpty("capabilities.optional");
        final Set<String> requiredInterfaces =
                readStringArrayAsSet(required, "interfaces");
        final Set<String> optionalInterfaces =
                readStringArrayAsSet(optional, "interfaces");

        // Per-interface subsections. Only the two Phase 1 covered
        // interfaces earn a section today — the loader silently ignores
        // subsections it doesn't know so a forward-compat manifest
        // doesn't fail to load.
        final Map<String, MethodPolicy> methodPolicies = new LinkedHashMap<>();
        parseGraphCallbacks(toml, methodPolicies);

        final HostAllowlist httpAllowlist = parseHttpAllowlist(toml);
        final CapabilityModel model = parseModel(toml);

        return new ExtensionManifest(
                name, version, signer,
                requiredInterfaces, optionalInterfaces,
                methodPolicies,
                httpAllowlist,
                model);
    }

    private static void parseGraphCallbacks(final TomlTable toml,
                                            final Map<String, MethodPolicy> out) {
        final TomlTable graph = toml.getTableOrEmpty("capabilities.graph-callbacks");
        if (graph.isEmpty()) return;
        final Set<String> allowed = readStringArrayAsSet(graph, "methods");
        // Empty methods array (or missing) means allow-all under this policy —
        // matches MethodPolicy's "empty allowlist means all" semantics.
        out.put("graph-callbacks",
                new MethodPolicy("graph-callbacks", allowed, Set.of()));
    }

    private static HostAllowlist parseHttpAllowlist(final TomlTable toml) {
        final TomlTable http = toml.getTableOrEmpty("capabilities.http-callbacks");
        if (http.isEmpty()) return HostAllowlist.ALLOW_NONE;
        final List<String> hosts = readStringArray(http, "allow_hosts");
        if (hosts.isEmpty()) return HostAllowlist.ALLOW_NONE;
        return new HostAllowlist(hosts);
    }

    private static CapabilityModel parseModel(final TomlTable toml) {
        // Strategy memo §13's per-extension declaration recommendation (b):
        // manifest names the desired model, admin policy can override.
        // Absent: fall back to AMBIENT for back-compat with pre-capability
        // extensions.
        final String raw = toml.getString("capabilities.model", () -> "");
        if (raw == null || raw.isEmpty()) return CapabilityModel.AMBIENT;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "invoker-subject":
            case "invoker_subject":
                return CapabilityModel.INVOKER_SUBJECT;
            case "ambient":
                return CapabilityModel.AMBIENT;
            default:
                throw new IllegalArgumentException(
                        "capabilities.model must be 'invoker-subject' or 'ambient', got: '"
                        + raw + "'");
        }
    }

    private static Set<String> readStringArrayAsSet(final TomlTable table, final String key) {
        return new LinkedHashSet<>(readStringArray(table, key));
    }

    private static List<String> readStringArray(final TomlTable table, final String key) {
        if (!table.isArray(key)) return new ArrayList<>();
        final TomlArray arr = table.getArray(key);
        if (arr == null) return new ArrayList<>();
        final List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            final Object v = arr.get(i);
            if (v instanceof String) {
                out.add((String) v);
            } else {
                throw new IllegalArgumentException(
                        "expected string entry at " + key + "[" + i + "], got "
                        + (v == null ? "null" : v.getClass().getSimpleName()));
            }
        }
        return out;
    }
}
