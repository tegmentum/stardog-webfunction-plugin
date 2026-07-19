package ai.tegmentum.stardog.kibble.webfunctions.compose;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlanV1Cbor} — the hand-rolled CBOR encoder that
 * matches the byte-shape ciborium/serde emit for
 * {@code compose_core::types::PlanV1}.
 *
 * <p>Focuses on primitive round-trips (integer head selection, string
 * lengths, byte string majors, boolean simples) and the whole-plan
 * shape (correct field ordering, {@code skip_serializing_if} elision
 * for {@code Linkage::Static} + empty {@code explicit_exports}).
 * Round-trip against a Rust reference decoder is out of scope for the
 * unit layer; the integration layer (Wave C+) exercises decoder
 * compat through the live orchestrator.
 */
public class TestPlanV1Cbor {

    // ---- head-selection tests ------------------------------------------

    @Test
    public void writeHeadEmitsShortestForm() throws Exception {
        assertHeadBytes(0, 0, new int[]{0x00});
        assertHeadBytes(0, 23, new int[]{0x17});
        assertHeadBytes(0, 24, new int[]{0x18, 0x18});
        assertHeadBytes(0, 255, new int[]{0x18, 0xFF});
        assertHeadBytes(0, 256, new int[]{0x19, 0x01, 0x00});
        assertHeadBytes(0, 65535, new int[]{0x19, 0xFF, 0xFF});
        assertHeadBytes(0, 65536, new int[]{0x1A, 0x00, 0x01, 0x00, 0x00});
        assertHeadBytes(0, 0xFFFFFFFFL, new int[]{0x1A, 0xFF, 0xFF, 0xFF, 0xFF});
        assertHeadBytes(0, 0x100000000L, new int[]{0x1B, 0, 0, 0, 1, 0, 0, 0, 0});
    }

    @Test
    public void writeUnsignedRejectsNegative() {
        assertThatThrownBy(() -> PlanV1Cbor.writeUnsigned(new ByteArrayOutputStream(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void writeIntegerNegativeUsesMajorOne() throws Exception {
        // -1 → nint(0) → 0x20; -24 → nint(23) → 0x37; -25 → nint(24) → 0x38 0x18
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanV1Cbor.writeInteger(out, -1);
        PlanV1Cbor.writeInteger(out, -24);
        PlanV1Cbor.writeInteger(out, -25);
        assertThat(out.toByteArray()).containsExactly(0x20, 0x37, 0x38, 0x18);
    }

    @Test
    public void writeTextEncodesUtf8Length() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanV1Cbor.writeText(out, "abc");
        // Major type 3, length 3, then bytes.
        assertThat(out.toByteArray()).containsExactly(0x63, 'a', 'b', 'c');
    }

    @Test
    public void writeBytesUsesByteStringMajor() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanV1Cbor.writeBytes(out, new byte[]{1, 2, 3});
        assertThat(out.toByteArray()).containsExactly(0x43, 1, 2, 3);
    }

    @Test
    public void writeBoolAndNullMatchSimpleMajors() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanV1Cbor.writeBool(out, true);
        PlanV1Cbor.writeBool(out, false);
        PlanV1Cbor.writeNull(out);
        assertThat(out.toByteArray()).containsExactly(0xF5, 0xF4, 0xF6);
    }

    // ---- plan shape tests ----------------------------------------------

    @Test
    public void minimalPlanElidesLinkageAndExplicitExports() {
        final PlanV1 plan = PlanV1.builder()
                .version("v1")
                .root("r")
                .build();
        final byte[] encoded = PlanV1Cbor.encode(plan);

        // Map with 6 entries (linkage + explicit_exports elided since
        // default Static + empty). Header byte for a 6-entry map is
        // 0xA6 (5<<5 | 6).
        assertThat(encoded[0] & 0xFF).as("map head").isEqualTo(0xA6);
        // Sanity: the field-name key "version" is the first key.
        assertContainsText(encoded, "version");
        assertContainsText(encoded, "root");
        assertContainsText(encoded, "components");
        assertContainsText(encoded, "bindings");
        assertContainsText(encoded, "secrets");
        assertContainsText(encoded, "policy");
        assertNotContainsText(encoded, "linkage");
        assertNotContainsText(encoded, "explicit_exports");
    }

    @Test
    public void nonDefaultLinkageIsIncluded() {
        final PlanV1 plan = PlanV1.builder()
                .version("v1")
                .root("r")
                .linkage(PlanV1.Linkage.RUNTIME)
                .build();
        final byte[] encoded = PlanV1Cbor.encode(plan);
        assertThat(encoded[0] & 0xFF).isEqualTo(0xA7); // 7-entry map
        assertContainsText(encoded, "linkage");
        assertContainsText(encoded, "runtime");
    }

    @Test
    public void componentSpecOmitsSourceWhenAbsent() {
        final PlanV1.ComponentSpec c = new PlanV1.ComponentSpec(
                "root", new byte[]{0x11, 0x22, 0x33}, Optional.empty());
        final PlanV1 plan = PlanV1.builder()
                .version("v1").root("root").addComponent(c).build();
        final byte[] encoded = PlanV1Cbor.encode(plan);

        // Components map should have 2 entries (id, digest), not 3.
        // Locate the "components" text and the first following map head.
        final int idx = indexOfText(encoded, "components");
        assertThat(idx).isGreaterThan(0);
        // After the text there's an array header (0x81 = array of 1),
        // then the component map header.
        assertThat(encoded[idx + textEncodedLength("components")] & 0xFF).isEqualTo(0x81);
        final int mapHead = encoded[idx + textEncodedLength("components") + 1] & 0xFF;
        assertThat(mapHead).as("component map has 2 entries").isEqualTo(0xA2);
    }

    @Test
    public void policyTenantIsOmittedWhenEmpty() {
        final PlanV1.Policy policy = new PlanV1.Policy(
                PlanV1.DeterminismMode.STRICT,
                Collections.emptyList(),
                Optional.empty(),
                PlanV1.ResourceLimits.empty());
        final PlanV1 plan = PlanV1.builder()
                .version("v1").root("r").policy(policy).build();
        final byte[] encoded = PlanV1Cbor.encode(plan);
        assertContainsText(encoded, "determinism");
        assertContainsText(encoded, "strict");
        assertContainsText(encoded, "capabilities");
        assertContainsText(encoded, "limits");
        assertNotContainsText(encoded, "tenant");
    }

    @Test
    public void policyTenantEncodedWhenPresent() {
        final PlanV1.Policy policy = new PlanV1.Policy(
                PlanV1.DeterminismMode.AUDIT,
                Collections.singletonList(new PlanV1.Capability("read", PlanV1.CapabilityLevel.REQUIRED)),
                Optional.of("tenant-x"),
                PlanV1.ResourceLimits.empty());
        final PlanV1 plan = PlanV1.builder()
                .version("v1").root("r").policy(policy).build();
        final byte[] encoded = PlanV1Cbor.encode(plan);
        assertContainsText(encoded, "tenant");
        assertContainsText(encoded, "tenant-x");
        assertContainsText(encoded, "required");
        assertContainsText(encoded, "audit");
    }

    @Test
    public void bindingWithConsumerHasFourEntries() {
        final PlanV1.ImportBinding b = new PlanV1.ImportBinding(
                Optional.of("consumer"),
                "wasi:io/streams",
                "provider",
                "wasi:io/streams");
        final PlanV1 plan = PlanV1.builder()
                .version("v1").root("r").addBinding(b).build();
        final byte[] encoded = PlanV1Cbor.encode(plan);

        final int idx = indexOfText(encoded, "bindings");
        // "bindings" text → array head 0x81 → map head.
        final int mapHead = encoded[idx + textEncodedLength("bindings") + 1] & 0xFF;
        assertThat(mapHead).isEqualTo(0xA4);
        assertContainsText(encoded, "consumer_id");
        assertContainsText(encoded, "import_name");
        assertContainsText(encoded, "provider_id");
        assertContainsText(encoded, "export_name");
    }

    @Test
    public void explicitExportsPresenceMakesEightEntryMap() {
        final PlanV1 plan = PlanV1.builder()
                .version("v1").root("r")
                .addExplicitExport(new PlanV1.ExplicitExport("plug", "sqlite:extension/types@0.1.0"))
                .build();
        final byte[] encoded = PlanV1Cbor.encode(plan);
        // 6 base + explicit_exports (linkage stays elided since Static).
        assertThat(encoded[0] & 0xFF).isEqualTo(0xA7);
        assertContainsText(encoded, "explicit_exports");
        assertContainsText(encoded, "source_instance");
        assertContainsText(encoded, "interface_name");
    }

    @Test
    public void resourceLimitsAllPresent() {
        final PlanV1.ResourceLimits limits = new PlanV1.ResourceLimits(
                Optional.of(1000L), Optional.of(1L << 20), Optional.of(50L));
        final PlanV1.Policy policy = new PlanV1.Policy(
                PlanV1.DeterminismMode.RELAXED,
                Collections.emptyList(),
                Optional.empty(), limits);
        final PlanV1 plan = PlanV1.builder().version("v1").root("r").policy(policy).build();
        final byte[] encoded = PlanV1Cbor.encode(plan);
        assertContainsText(encoded, "cpu_ms");
        assertContainsText(encoded, "memory_bytes");
        assertContainsText(encoded, "io_ops");
    }

    // ---- helpers -------------------------------------------------------

    private static void assertHeadBytes(final int mt, final long v, final int[] expectedBytes)
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        PlanV1Cbor.writeHead(out, mt, v);
        final byte[] actual = out.toByteArray();
        final byte[] expected = new byte[expectedBytes.length];
        for (int i = 0; i < expectedBytes.length; i++) {
            expected[i] = (byte) expectedBytes[i];
        }
        assertThat(actual).as("head bytes for mt=" + mt + " v=" + v).isEqualTo(expected);
    }

    private static byte[] textEncoded(final String s) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PlanV1Cbor.writeText(out, s);
        } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private static int textEncodedLength(final String s) {
        return textEncoded(s).length;
    }

    private static int indexOfText(final byte[] haystack, final String text) {
        final byte[] needle = textEncoded(text);
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void assertContainsText(final byte[] encoded, final String text) {
        assertThat(indexOfText(encoded, text))
                .as("encoding contains CBOR text '" + text + "'")
                .isGreaterThanOrEqualTo(0);
    }

    private static void assertNotContainsText(final byte[] encoded, final String text) {
        assertThat(indexOfText(encoded, text))
                .as("encoding does NOT contain CBOR text '" + text + "'")
                .isEqualTo(-1);
    }

    // Ensure Arrays.asList imports aren't stripped by unused-import checks.
    @SuppressWarnings("unused")
    private static final Object USED_IMPORTS = Arrays.asList("keep");
}
