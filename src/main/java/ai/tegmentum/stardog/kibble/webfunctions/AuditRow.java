package ai.tegmentum.stardog.kibble.webfunctions;

/**
 * Marker for the two Phase 1 audit-row shapes so the Phase 6 disk sink can
 * accept both without coupling to a specific record type.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link AttributionRow} — fuel-attribution rows written by
 *       {@link AttributionRing}.</li>
 *   <li>{@link CapabilityAuditRow} — capability-audit rows written by
 *       {@link CapabilityAttributionRing}.</li>
 * </ul>
 *
 * <p>Each row serializes itself to a single-line NDJSON representation via
 * {@link #toNdjsonLine()}. NDJSON is one JSON object per line — greppable,
 * tailable, ELK-ingestable — and the hand-rolled writer avoids adding a
 * Jackson dependency to the plugin (the rows are simple flat records; the
 * escape rules fit in the {@link #escapeJson(String, StringBuilder)} helper).
 *
 * <p>The returned line MUST NOT include a trailing newline — the sink appends
 * one after each row so the boundary between rows is the sink's concern
 * (matches how systemd-journal and Fluent Bit consume NDJSON files).
 */
public interface AuditRow {

    /**
     * Serialize this row to a single-line NDJSON representation, WITHOUT
     * the trailing newline. Field ordering is stable per-row-type so
     * downstream tooling that diffs sink output across releases sees a
     * predictable shape.
     */
    String toNdjsonLine();

    /**
     * Append the JSON-escaped form of {@code s} to {@code out}. Handles the
     * escape rules the JSON grammar demands (RFC 8259 §7): the two
     * structural characters {@code "} and {@code \}, the ASCII control
     * range {@code U+0000..U+001F}, and — as a convenience for consumers
     * that don't decode non-BMP surrogates cleanly — leaves valid Unicode
     * code points above {@code U+007F} as-is (the file is written UTF-8).
     *
     * <p>Null-safe: null becomes the four-character literal {@code null}
     * (no quotes) so the caller decides whether to wrap the field in
     * quotes. Rows in practice enforce non-null on every field via record
     * compact constructors, so the null path is defensive rather than a
     * regular case.
     */
    static void escapeJson(final String s, final StringBuilder out) {
        if (s == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        // Emit as a JSON \\uXXXX escape for other C0 controls
                        // (broken up in this comment so the Java compiler
                        // doesn't try to parse a bare backslash-u sequence).
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
