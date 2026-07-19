package ai.tegmentum.stardog.kibble.webfunctions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Hand-rolled minimal parser for WebAssembly custom sections.
 *
 * <p>Adapted from the capability-ask probe (2026-07 scratchpad). The
 * webassembly4j substrate does not currently expose an API for reading
 * arbitrary named custom sections; the capability-ask memo (§6) commits
 * the plugin to a hand-rolled reader instead of blocking on a substrate
 * change.
 *
 * <p>Wire format — identical for core modules and components:
 * <pre>
 *   magic (4 bytes: 0x00 'a' 's' 'm')
 *   version (4 bytes)
 *   section* : id (u8) | size (LEB128 u32) | payload[size]
 * </pre>
 * A section with id {@code 0} is a custom section; its payload is:
 * <pre>
 *   name_len (LEB128 u32) | name (UTF-8) | data
 * </pre>
 *
 * <p>Components differ from modules only in the version bytes
 * ({@code 0x0d 0x00 0x01 0x00} vs {@code 0x01 0x00 0x00 0x00}); the outer
 * framing is byte-for-byte identical, so this reader handles both
 * without discrimination. Nested custom sections embedded inside a
 * component's inline modules are <em>not</em> walked — only outer-level
 * ones are surfaced. Capability ask lives at the outer level by
 * convention.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #extractSection(byte[], String)} — targeted single-name
 *       lookup used by the capability-ask hot path in
 *       {@link StardogWasmInstance}.</li>
 *   <li>{@link #extractAllCustomSections(byte[])} — diagnostic
 *       "list-all" for admin tooling. Returns first-encountered payload
 *       per name (duplicates ignored, matching wasm's own convention).</li>
 * </ul>
 *
 * <p>Malformed input — missing magic, truncated header, LEB128 that
 * runs off the buffer, section that overruns the buffer end — throws
 * {@link MalformedWasmException}. Callers are expected to log + proceed
 * rather than fail the invocation; extraction is best-effort per §6 of
 * the capability-ask memo.
 */
public final class WasmCustomSectionReader {

    private WasmCustomSectionReader() {}

    /**
     * Look up a single custom section by name. Returns the payload bytes
     * verbatim (no framing left in) when present, empty when the wasm
     * carries no custom section by that name.
     *
     * @throws MalformedWasmException when the input bytes do not parse as
     *         a valid WebAssembly binary.
     */
    public static Optional<byte[]> extractSection(final byte[] wasmBytes,
                                                  final String sectionName) {
        Objects.requireNonNull(sectionName, "sectionName");
        if (wasmBytes == null) {
            throw new MalformedWasmException("wasm bytes are null");
        }
        final Map<String, byte[]> all = extractAllCustomSections(wasmBytes);
        return Optional.ofNullable(all.get(sectionName));
    }

    /**
     * Walk every outer-level custom section in the module or component.
     * Returns a map keyed by section name in encounter order; on
     * duplicate names, first encountered wins. Empty map when the wasm
     * carries no custom sections at all.
     *
     * @throws MalformedWasmException when the input bytes do not parse as
     *         a valid WebAssembly binary.
     */
    public static Map<String, byte[]> extractAllCustomSections(final byte[] wasmBytes) {
        if (wasmBytes == null) {
            throw new MalformedWasmException("wasm bytes are null");
        }
        if (wasmBytes.length < 8) {
            throw new MalformedWasmException(
                    "wasm too short (need at least 8 bytes for magic+version, got "
                            + wasmBytes.length + ")");
        }
        // Verify \0asm magic. Both core modules and components share this
        // prefix; only the version-word layer byte differs.
        if (wasmBytes[0] != 0x00 || wasmBytes[1] != 0x61
                || wasmBytes[2] != 0x73 || wasmBytes[3] != 0x6d) {
            throw new MalformedWasmException(
                    "not a wasm binary (missing \\0asm magic at offset 0)");
        }

        final Map<String, byte[]> out = new LinkedHashMap<>();
        int pos = 8; // skip magic (4) + version (4)
        while (pos < wasmBytes.length) {
            if (pos >= wasmBytes.length) break;
            final int id = wasmBytes[pos++] & 0xff;
            final long[] sizeAndConsumed = readUleb(wasmBytes, pos);
            final int size = (int) sizeAndConsumed[0];
            pos = (int) sizeAndConsumed[1];
            if (size < 0 || (long) pos + (long) size > wasmBytes.length) {
                throw new MalformedWasmException(
                        "section id=" + id + " at offset " + (pos - 1)
                                + " declares size " + size
                                + " that runs past buffer end ("
                                + wasmBytes.length + ")");
            }
            final int sectionEnd = pos + size;
            if (id == 0) {
                // Custom section: name_len (LEB) | name | data
                final long[] nameLenAndConsumed = readUleb(wasmBytes, pos);
                final int nameLen = (int) nameLenAndConsumed[0];
                final int nameStart = (int) nameLenAndConsumed[1];
                if (nameLen < 0 || (long) nameStart + (long) nameLen > sectionEnd) {
                    throw new MalformedWasmException(
                            "custom section at offset " + (pos - 1)
                                    + " declares name length " + nameLen
                                    + " that overruns section end");
                }
                final String name = new String(
                        wasmBytes, nameStart, nameLen, StandardCharsets.UTF_8);
                final int payloadStart = nameStart + nameLen;
                final int payloadLen = sectionEnd - payloadStart;
                final byte[] payload = new byte[payloadLen];
                System.arraycopy(wasmBytes, payloadStart, payload, 0, payloadLen);
                // First-encountered wins on duplicate names — mirrors the
                // wasm-tools + wasmparser convention (custom sections are
                // append-only in the spec; duplicates are legal but the
                // first is canonical).
                out.putIfAbsent(name, payload);
            }
            pos = sectionEnd;
        }
        return out;
    }

    /**
     * Decode a single ULEB128 varuint starting at {@code start}. Returns
     * {@code {value, newPos}} — same shape as the scratchpad probe so a
     * one-line adapter reads the same on both sides.
     *
     * @throws MalformedWasmException when the varuint runs off the buffer
     *         or is longer than 64 bits (unreachable for wasm's u32-max
     *         sections in practice, but a defensive cap protects against
     *         a corrupted stream).
     */
    private static long[] readUleb(final byte[] bytes, final int start) {
        long result = 0;
        int shift = 0;
        int pos = start;
        while (true) {
            if (pos >= bytes.length) {
                throw new MalformedWasmException(
                        "LEB128 varuint at offset " + start + " ran off buffer end");
            }
            final int b = bytes[pos++] & 0xff;
            result |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) return new long[]{result, pos};
            shift += 7;
            if (shift >= 64) {
                throw new MalformedWasmException(
                        "LEB128 varuint at offset " + start + " exceeded 64 bits");
            }
        }
    }
}
