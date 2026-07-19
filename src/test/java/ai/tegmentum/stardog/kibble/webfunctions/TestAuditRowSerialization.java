package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 audit-row NDJSON serialization — the disk sink writes one
 * {@code toNdjsonLine()} per row per line. Test covers:
 *
 * <ul>
 *   <li>Round-trippable field shape for both {@link AttributionRow} and
 *       {@link CapabilityAuditRow} — every non-null field appears in the
 *       output, the {@code type} discriminator identifies the row type,
 *       and JSON structural characters in string fields are escaped so
 *       the line remains parseable.</li>
 *   <li>Noop-sink is genuinely no-op — {@link NoopAuditSink#write} on a
 *       null-ish row doesn't throw and doesn't do anything observable.</li>
 * </ul>
 *
 * <p>No wasm fixture — pure record + string serialization.
 */
public class TestAuditRowSerialization {

    @Test
    public void fuelRowSerializesAllFields() {
        final Instant ts = Instant.parse("2026-07-19T15:00:00Z");
        final AttributionRow row = new AttributionRow(
                ts,
                "alice",
                "acme",
                "ipfs://Qm12345/toUpper.wasm",
                4200L,
                AttributionRow.Outcome.SUCCESS,
                "query-abc");
        final String line = row.toNdjsonLine();
        // No trailing newline — the sink appends one.
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        // Discriminator + every field present with the record's values.
        assertThat(line).contains("\"type\":\"fuel\"");
        assertThat(line).contains("\"timestamp\":\"2026-07-19T15:00:00Z\"");
        assertThat(line).contains("\"userId\":\"alice\"");
        assertThat(line).contains("\"orgId\":\"acme\"");
        assertThat(line).contains("\"extensionUri\":\"ipfs://Qm12345/toUpper.wasm\"");
        assertThat(line).contains("\"fuelConsumed\":4200");
        assertThat(line).contains("\"outcome\":\"SUCCESS\"");
        assertThat(line).contains("\"queryId\":\"query-abc\"");
        // Well-formed at the byte level — starts with {, ends with }.
        assertThat(line.charAt(0)).isEqualTo('{');
        assertThat(line.charAt(line.length() - 1)).isEqualTo('}');
    }

    @Test
    public void capabilityRowSerializesAllFields() {
        final Instant ts = Instant.parse("2026-07-19T15:00:01Z");
        final CapabilityAuditRow row = new CapabilityAuditRow(
                ts,
                "bob",
                "acme",
                "ipfs://Qm67890/geocoder.wasm",
                "http-callbacks",
                "get",
                "api.example.com",
                CapabilityAuditRow.Outcome.DENIED,
                "REASON_HOST_DENIED");
        final String line = row.toNdjsonLine();
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        assertThat(line).contains("\"type\":\"capability\"");
        assertThat(line).contains("\"timestamp\":\"2026-07-19T15:00:01Z\"");
        assertThat(line).contains("\"userId\":\"bob\"");
        assertThat(line).contains("\"orgId\":\"acme\"");
        assertThat(line).contains("\"extensionUri\":\"ipfs://Qm67890/geocoder.wasm\"");
        assertThat(line).contains("\"interfaceName\":\"http-callbacks\"");
        assertThat(line).contains("\"method\":\"get\"");
        assertThat(line).contains("\"argumentsSummary\":\"api.example.com\"");
        assertThat(line).contains("\"outcome\":\"DENIED\"");
        assertThat(line).contains("\"denyReason\":\"REASON_HOST_DENIED\"");
    }

    /**
     * A JSON structural character ({@code "} or {@code \}) or a control
     * byte in a field value must be escaped so the produced line is still
     * exactly one JSON object.
     */
    @Test
    public void structuralCharactersInFieldsAreEscaped() {
        final CapabilityAuditRow row = new CapabilityAuditRow(
                Instant.parse("2026-07-19T15:00:00Z"),
                "quote\"user",           // embedded quote
                "back\\slash",           // embedded backslash
                "file:///tmp/x.wasm",
                "if",
                "tab\there",             // embedded tab (\t)
                "line\nbreak",           // embedded newline (\n)
                CapabilityAuditRow.Outcome.GRANTED,
                "");
        final String line = row.toNdjsonLine();
        // No raw newline / raw tab / bare quote breaks the line format.
        assertThat(line).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(line).contains("\\\"user");   // "
        assertThat(line).contains("\\\\slash");  // \
        assertThat(line).contains("\\there");    // \t
        assertThat(line).contains("\\nbreak");   // \n
    }

    /**
     * The {@link NoopAuditSink} accepts any row (including a hand-built
     * one) and does not observably affect the in-memory ring. Also
     * exercises the {@code flush}/{@code close} idempotency contract.
     */
    @Test
    public void noopSinkIsTrulyNoOp() {
        final AuditSink sink = NoopAuditSink.INSTANCE;
        final AttributionRow row = new AttributionRow(
                Instant.now(), "", "", "file:///noop.wasm", 0L,
                AttributionRow.Outcome.SUCCESS, "");
        // Doesn't throw; nothing to assert about internal state — the
        // singleton is stateless.
        sink.write(row);
        sink.write(row);
        sink.flush();
        sink.close();
        sink.close(); // idempotent
    }
}
