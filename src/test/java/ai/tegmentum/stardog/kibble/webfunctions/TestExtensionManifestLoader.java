package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Capability wave Phase 1c — TOML manifest loader.
 *
 * <p>Three cases per the sub-phase plan:
 * <ul>
 *   <li><b>Happy path</b> — a well-formed sidecar parses to the expected
 *       {@link ExtensionManifest} shape.</li>
 *   <li><b>Missing sidecar</b> — loading against a URL with no
 *       accompanying {@code .toml} throws
 *       {@link WfCapabilityError.ManifestMalformed} with the {@code
 *       "manifest not found"} human message.</li>
 *   <li><b>Malformed TOML</b> — a syntactically invalid manifest throws
 *       {@link WfCapabilityError.ManifestMalformed} carrying the tomlj
 *       parse error details.</li>
 * </ul>
 *
 * <p>Uses {@code file://} URLs against a {@link TemporaryFolder} — same
 * shape as {@code TestFuelMetering}'s wasm-file assumption but no
 * cargo-build dependency.
 */
public class TestExtensionManifestLoader {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File wasmFile;

    @Before
    public void setUp() throws IOException {
        wasmFile = tmp.newFile("ext.wasm");
        // Zero-byte wasm — the loader never reads the wasm itself.
    }

    @After
    public void tearDown() {}

    @Test
    public void happyPathParsesEveryDeclaredSection() throws IOException {
        final File manifestFile = new File(wasmFile.getPath() + ".toml");
        writeSidecar(manifestFile,
                "[package]\n" +
                "name = \"my-extension\"\n" +
                "version = \"0.3.0\"\n" +
                "signer = \"did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK\"\n" +
                "\n" +
                "[capabilities.required]\n" +
                "interfaces = [\"graph-callbacks\", \"http-callbacks\"]\n" +
                "\n" +
                "[capabilities.optional]\n" +
                "interfaces = [\"fulltext-callbacks\"]\n" +
                "\n" +
                "[capabilities.http-callbacks]\n" +
                "allow_hosts = [\"api.acme.com\", \"*.example.org\"]\n" +
                "\n" +
                "[capabilities.graph-callbacks]\n" +
                "methods = [\"execute-query\"]\n" +
                "\n" +
                "[capabilities]\n" +
                "model = \"invoker-subject\"\n");

        final ExtensionManifest m = ExtensionManifestLoader.load(wasmFile.toURI().toURL());
        assertThat(m.name()).isEqualTo("my-extension");
        assertThat(m.version()).isEqualTo("0.3.0");
        assertThat(m.signer()).startsWith("did:key:");
        assertThat(m.requiredInterfaces())
                .containsExactly("graph-callbacks", "http-callbacks");
        assertThat(m.optionalInterfaces())
                .containsExactly("fulltext-callbacks");
        assertThat(m.httpAllowlist().matches("api.acme.com")).isTrue();
        assertThat(m.httpAllowlist().matches("api.example.org")).isTrue();
        assertThat(m.httpAllowlist().matches("other.host")).isFalse();
        assertThat(m.methodPolicies()).containsKey("graph-callbacks");
        assertThat(m.methodPolicies().get("graph-callbacks").allows("execute-query")).isTrue();
        assertThat(m.methodPolicies().get("graph-callbacks").allows("execute-update")).isFalse();
        assertThat(m.model()).isEqualTo(CapabilityModel.INVOKER_SUBJECT);
    }

    @Test
    public void missingSidecarThrowsManifestMalformed() throws IOException {
        // No sidecar written — loader must fail cleanly.
        final URL wasmUrl = wasmFile.toURI().toURL();
        final Throwable thrown = catchThrowable(() -> ExtensionManifestLoader.load(wasmUrl));
        assertThat(thrown)
                .isInstanceOf(WfCapabilityError.ManifestMalformed.class);
        final WfCapabilityError.ManifestMalformed err = (WfCapabilityError.ManifestMalformed) thrown;
        assertThat(err.parseError()).contains("manifest not found");
        // The extensionUri on the error points at the sidecar we probed for.
        assertThat(err.extensionUri()).endsWith(".toml");
    }

    @Test
    public void malformedTomlThrowsManifestMalformedWithParseDetails() throws IOException {
        final File manifestFile = new File(wasmFile.getPath() + ".toml");
        // Deliberate syntax error — a stray opening bracket.
        writeSidecar(manifestFile,
                "[package\n" +
                "name = \"broken\"\n");

        final Throwable thrown = catchThrowable(
                () -> ExtensionManifestLoader.load(wasmFile.toURI().toURL()));
        assertThat(thrown).isInstanceOf(WfCapabilityError.ManifestMalformed.class);
        final WfCapabilityError.ManifestMalformed err = (WfCapabilityError.ManifestMalformed) thrown;
        assertThat(err.parseError()).isNotEmpty();
        // Parse errors from tomlj include source position; assert it's threaded through.
        assertThat(err.parseError()).containsIgnoringCase("line");
    }

    @Test
    public void absentSectionsProduceEmptyDeclarations() throws IOException {
        // A minimal manifest — nothing declared. Loader must not throw.
        final File manifestFile = new File(wasmFile.getPath() + ".toml");
        writeSidecar(manifestFile,
                "[package]\n" +
                "name = \"minimal\"\n" +
                "version = \"0.0.1\"\n");

        final ExtensionManifest m = ExtensionManifestLoader.load(wasmFile.toURI().toURL());
        assertThat(m.name()).isEqualTo("minimal");
        assertThat(m.requiredInterfaces()).isEmpty();
        assertThat(m.optionalInterfaces()).isEmpty();
        assertThat(m.methodPolicies()).isEmpty();
        // Deny-all HTTP when the section is absent.
        assertThat(m.httpAllowlist().matches("api.acme.com")).isFalse();
        // Default model when unspecified.
        assertThat(m.model()).isEqualTo(CapabilityModel.AMBIENT);
    }

    @Test
    public void unknownModelValueRejected() throws IOException {
        final File manifestFile = new File(wasmFile.getPath() + ".toml");
        writeSidecar(manifestFile,
                "[capabilities]\n" +
                "model = \"nonsense\"\n");

        final Throwable thrown = catchThrowable(
                () -> ExtensionManifestLoader.load(wasmFile.toURI().toURL()));
        assertThat(thrown).isInstanceOf(WfCapabilityError.ManifestMalformed.class);
        assertThat(((WfCapabilityError.ManifestMalformed) thrown).parseError())
                .contains("model");
    }

    @Test
    public void ambientModelAcceptedByName() throws IOException {
        final File manifestFile = new File(wasmFile.getPath() + ".toml");
        writeSidecar(manifestFile,
                "[capabilities]\n" +
                "model = \"ambient\"\n");
        final ExtensionManifest m = ExtensionManifestLoader.load(wasmFile.toURI().toURL());
        assertThat(m.model()).isEqualTo(CapabilityModel.AMBIENT);
    }

    @Test
    public void sidecarUrlForAppendsDotTomlSuffix() throws IOException {
        final URL wasm = wasmFile.toURI().toURL();
        final URL sidecar = ExtensionManifestLoader.sidecarUrlFor(wasm);
        assertThat(sidecar.toString()).isEqualTo(wasm.toString() + ".toml");
    }

    @Test
    public void parseManifestAcceptsPrebuiltTomlTable() {
        // Direct-parse path — bypasses fetch to prove the parser is
        // independently unit-testable.
        final TomlParseResult toml = Toml.parse(
                "[package]\n" +
                "name = \"direct\"\n" +
                "version = \"9\"\n" +
                "signer = \"\"\n" +
                "[capabilities.required]\n" +
                "interfaces = [\"graph-callbacks\"]\n");
        final ExtensionManifest m = ExtensionManifestLoader.parseManifest(toml);
        assertThat(m.name()).isEqualTo("direct");
        assertThat(m.requiredInterfaces()).containsExactly("graph-callbacks");
    }

    private static void writeSidecar(final File file, final String contents) throws IOException {
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }
}
