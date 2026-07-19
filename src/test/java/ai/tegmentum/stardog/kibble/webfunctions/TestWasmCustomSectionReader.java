package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capability-ask CA1 — {@link WasmCustomSectionReader}.
 *
 * <p>Round-trips synthetic wasm byte streams through the hand-rolled
 * parser to cover: single-section lookup, list-all diagnostic path,
 * missing section returning {@link Optional#empty()}, malformed inputs
 * (wrong magic, truncated header, section overrun) throwing
 * {@link MalformedWasmException}. Uses tiny in-process builders so no
 * external {@code .wasm} fixtures are needed to prove the parser.
 */
public class TestWasmCustomSectionReader {

    // ---- helpers ------------------------------------------------------

    /**
     * Build a minimal wasm module header + zero-or-more custom sections
     * for round-tripping through the reader. Real modules would have
     * type/func/code sections after; the reader only cares about the
     * outer section framing so we can omit them and it will still parse.
     */
    private static byte[] buildWasm(final CustomSection... sections) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // magic \0asm
        out.write(0x00); out.write(0x61); out.write(0x73); out.write(0x6d);
        // version 1 for core module
        out.write(0x01); out.write(0x00); out.write(0x00); out.write(0x00);
        for (CustomSection s : sections) {
            final byte[] nameBytes = s.name.getBytes(StandardCharsets.UTF_8);
            final ByteArrayOutputStream payload = new ByteArrayOutputStream();
            writeUleb(payload, nameBytes.length);
            payload.write(nameBytes, 0, nameBytes.length);
            payload.write(s.data, 0, s.data.length);
            final byte[] payloadBytes = payload.toByteArray();
            out.write(0x00); // custom-section id
            writeUleb(out, payloadBytes.length);
            out.write(payloadBytes, 0, payloadBytes.length);
        }
        return out.toByteArray();
    }

    /** Build a minimal component header (layer=1 in the version word). */
    private static byte[] buildComponentHeader() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); out.write(0x61); out.write(0x73); out.write(0x6d);
        out.write(0x0d); out.write(0x00); out.write(0x01); out.write(0x00);
        return out.toByteArray();
    }

    private static void writeUleb(final ByteArrayOutputStream out, int value) {
        while (true) {
            final int b = value & 0x7f;
            value >>>= 7;
            if (value == 0) {
                out.write(b);
                return;
            }
            out.write(b | 0x80);
        }
    }

    private static final class CustomSection {
        final String name;
        final byte[] data;
        CustomSection(final String name, final byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    // ---- tests --------------------------------------------------------

    @Test
    public void extractSectionReturnsPayloadWhenPresent() {
        final byte[] ttl = "<> a <urn:test> .".getBytes(StandardCharsets.UTF_8);
        final byte[] wasm = buildWasm(new CustomSection("stardog.capability-ask", ttl));

        final Optional<byte[]> payload = WasmCustomSectionReader.extractSection(
                wasm, "stardog.capability-ask");

        assertThat(payload).isPresent();
        assertThat(payload.get()).isEqualTo(ttl);
    }

    @Test
    public void extractSectionReturnsEmptyWhenAbsent() {
        final byte[] wasm = buildWasm(
                new CustomSection("name", "hello".getBytes(StandardCharsets.UTF_8)));

        final Optional<byte[]> ask = WasmCustomSectionReader.extractSection(
                wasm, "stardog.capability-ask");

        assertThat(ask).isEmpty();
    }

    @Test
    public void extractSectionReturnsEmptyWhenNoCustomSectionsAtAll() {
        final byte[] wasm = buildWasm(); // magic + version only

        final Optional<byte[]> ask = WasmCustomSectionReader.extractSection(
                wasm, "stardog.capability-ask");

        assertThat(ask).isEmpty();
    }

    @Test
    public void extractAllCustomSectionsListsEveryOuterSection() {
        final byte[] a = "aaa".getBytes(StandardCharsets.UTF_8);
        final byte[] b = "bbbb".getBytes(StandardCharsets.UTF_8);
        final byte[] c = "cccccc".getBytes(StandardCharsets.UTF_8);
        final byte[] wasm = buildWasm(
                new CustomSection("first", a),
                new CustomSection("second", b),
                new CustomSection("third", c));

        final Map<String, byte[]> all = WasmCustomSectionReader.extractAllCustomSections(wasm);

        assertThat(all).containsOnlyKeys("first", "second", "third");
        assertThat(all.get("first")).isEqualTo(a);
        assertThat(all.get("second")).isEqualTo(b);
        assertThat(all.get("third")).isEqualTo(c);
    }

    @Test
    public void extractAllPreservesFirstOnDuplicateName() {
        final byte[] first = "first-wins".getBytes(StandardCharsets.UTF_8);
        final byte[] second = "second-loses".getBytes(StandardCharsets.UTF_8);
        final byte[] wasm = buildWasm(
                new CustomSection("dup", first),
                new CustomSection("dup", second));

        final Map<String, byte[]> all = WasmCustomSectionReader.extractAllCustomSections(wasm);

        assertThat(all).hasSize(1);
        assertThat(all.get("dup")).isEqualTo(first);
    }

    @Test
    public void extractSectionHandlesComponentHeader() {
        // Components share the outer framing with modules — only the
        // version-word layer byte differs. The reader must not care.
        final byte[] header = buildComponentHeader();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header, 0, header.length);
        final byte[] name = "component.custom".getBytes(StandardCharsets.UTF_8);
        final byte[] data = "cdata".getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeUleb(payload, name.length);
        payload.write(name, 0, name.length);
        payload.write(data, 0, data.length);
        final byte[] payloadBytes = payload.toByteArray();
        out.write(0x00);
        writeUleb(out, payloadBytes.length);
        out.write(payloadBytes, 0, payloadBytes.length);

        final Optional<byte[]> found = WasmCustomSectionReader.extractSection(
                out.toByteArray(), "component.custom");

        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(data);
    }

    @Test
    public void extractSectionThrowsOnMissingMagic() {
        final byte[] junk = new byte[]{'n', 'o', 't', 'w', 'a', 's', 'm', 0};

        assertThatThrownBy(() -> WasmCustomSectionReader.extractSection(junk, "x"))
                .isInstanceOf(MalformedWasmException.class)
                .hasMessageContaining("magic");
    }

    @Test
    public void extractSectionThrowsOnTruncatedHeader() {
        final byte[] tooShort = new byte[]{0x00, 0x61, 0x73}; // 3 bytes, missing rest

        assertThatThrownBy(() -> WasmCustomSectionReader.extractSection(tooShort, "x"))
                .isInstanceOf(MalformedWasmException.class)
                .hasMessageContaining("too short");
    }

    @Test
    public void extractSectionThrowsOnSectionOverrun() {
        // Build magic+version, then a custom-section id (0x00) with a
        // wildly-oversized declared length so payload reads past the end.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); out.write(0x61); out.write(0x73); out.write(0x6d);
        out.write(0x01); out.write(0x00); out.write(0x00); out.write(0x00);
        out.write(0x00); // custom section id
        // Declared size 200 — no such bytes follow, so this must trap.
        writeUleb(out, 200);

        assertThatThrownBy(() -> WasmCustomSectionReader.extractAllCustomSections(out.toByteArray()))
                .isInstanceOf(MalformedWasmException.class)
                .hasMessageContaining("runs past buffer end");
    }

    @Test
    public void extractSectionThrowsOnNullBytes() {
        assertThatThrownBy(() -> WasmCustomSectionReader.extractSection(null, "x"))
                .isInstanceOf(MalformedWasmException.class);
    }
}
