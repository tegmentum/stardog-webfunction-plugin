package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Hand-rolled CBOR (RFC 8949) encoder for {@link PlanV1} that matches
 * the byte-shape produced by the upstream Rust crate's
 * {@code ciborium::into_writer(plan, ...)} — the canonical form the
 * compose orchestrator's serde-generated deserializer expects.
 *
 * <p>Rationale for a hand-rolled encoder: Stardog's transitive
 * dependency tree ships jackson-core and jackson-databind but not
 * jackson-dataformat-cbor, and adding a new runtime dependency to the
 * shaded plugin jar is a heavier lift than the ~200 LOC needed to emit
 * the well-typed subset of CBOR this file consumes.
 *
 * <p>Compatibility contract with ciborium/serde:
 * <ul>
 *   <li>Structs are encoded as CBOR maps (major type 5) whose keys are
 *       text strings in the Rust struct's field declaration order —
 *       serde-derived {@code Serialize} preserves that order and
 *       ciborium's writer emits fields in the order the {@code Serializer}
 *       is called.</li>
 *   <li>{@code Vec<T>} is a CBOR array (major type 4).</li>
 *   <li>{@code Vec<u8>} (digest bytes) is a byte string (major type 2).</li>
 *   <li>{@code String} is a text string (major type 3).</li>
 *   <li>{@code Option<T>} with {@code skip_serializing_if = Option::is_none}
 *       omits the entry entirely when None; otherwise emits the wrapped
 *       value at that map slot (serde-with-flatten-unit shape).</li>
 *   <li>Unit-variant enums with {@code #[serde(rename_all = "lowercase")]}
 *       or {@code "kebab-case"} serialize as text strings.</li>
 *   <li>Integers use the shortest CBOR head that encodes their value —
 *       ciborium and the spec both prescribe this.</li>
 *   <li>Booleans/{@code null} use the simple-value majors as in the spec.</li>
 * </ul>
 *
 * <p>Not full canonical CBOR (no map-key sort — struct order beats
 * lexicographic here since ciborium/serde is our reference decoder),
 * but produces bytes that {@code ciborium::from_reader} decodes into
 * an equivalent {@code PlanV1}.
 */
public final class PlanV1Cbor {

    // CBOR major types (upper three bits of the initial byte).
    private static final int MT_UINT      = 0;
    private static final int MT_NEG_INT   = 1;
    private static final int MT_BYTES     = 2;
    private static final int MT_TEXT      = 3;
    private static final int MT_ARRAY     = 4;
    private static final int MT_MAP       = 5;
    private static final int MT_TAG       = 6;
    private static final int MT_SIMPLE    = 7;

    // Simple values (RFC 8949 §3.3).
    private static final int SIMPLE_FALSE = 20;
    private static final int SIMPLE_TRUE  = 21;
    private static final int SIMPLE_NULL  = 22;

    private PlanV1Cbor() {}

    /**
     * Canonical CBOR bytes for a {@link PlanV1} — safe to hand
     * directly to the orchestrator's {@code sys:compose/emit#compose}
     * export.
     */
    public static byte[] encode(final PlanV1 plan) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try {
            writePlan(out, plan);
        } catch (IOException e) {
            // ByteArrayOutputStream doesn't throw IOException — future-proof
            // catch keeps the public method exception-clean.
            throw new IllegalStateException("in-memory CBOR write failed", e);
        }
        return out.toByteArray();
    }

    // --- top-level PlanV1 struct encoder --------------------------------

    static void writePlan(final ByteArrayOutputStream out, final PlanV1 plan) throws IOException {
        // Field entries: struct-declaration order. Optional/empty-vector
        // fields are conditionally omitted to mirror the upstream
        // #[serde(default, skip_serializing_if = ...)] annotations on
        // linkage and explicit_exports.
        int n = 6; // version, root, components, bindings, secrets, policy
        final boolean writeLinkage = plan.linkage() != PlanV1.Linkage.STATIC;
        final boolean writeExports = !plan.explicitExports().isEmpty();
        if (writeLinkage) n++;
        if (writeExports) n++;
        writeMapHead(out, n);
        writeText(out, "version");         writeText(out, plan.version());
        writeText(out, "root");            writeText(out, plan.root());
        writeText(out, "components");      writeComponentsArray(out, plan.components());
        writeText(out, "bindings");        writeBindingsArray(out, plan.bindings());
        writeText(out, "secrets");         writeSecretsArray(out, plan.secrets());
        writeText(out, "policy");          writePolicy(out, plan.policy());
        if (writeLinkage) {
            writeText(out, "linkage");
            writeText(out, plan.linkage().wire());
        }
        if (writeExports) {
            writeText(out, "explicit_exports");
            writeExplicitExportsArray(out, plan.explicitExports());
        }
    }

    private static void writeComponentsArray(final ByteArrayOutputStream out,
                                             final List<PlanV1.ComponentSpec> components) throws IOException {
        writeArrayHead(out, components.size());
        for (final PlanV1.ComponentSpec c : components) {
            writeComponent(out, c);
        }
    }

    private static void writeComponent(final ByteArrayOutputStream out,
                                       final PlanV1.ComponentSpec c) throws IOException {
        final boolean hasSource = c.source().isPresent();
        writeMapHead(out, hasSource ? 3 : 2);
        writeText(out, "id");     writeText(out, c.id());
        writeText(out, "digest"); writeBytes(out, c.digest());
        if (hasSource) {
            writeText(out, "source");
            writeText(out, c.source().get());
        }
    }

    private static void writeBindingsArray(final ByteArrayOutputStream out,
                                           final List<PlanV1.ImportBinding> bindings) throws IOException {
        writeArrayHead(out, bindings.size());
        for (final PlanV1.ImportBinding b : bindings) {
            writeBinding(out, b);
        }
    }

    private static void writeBinding(final ByteArrayOutputStream out,
                                     final PlanV1.ImportBinding b) throws IOException {
        final boolean hasConsumer = b.consumerId().isPresent();
        writeMapHead(out, hasConsumer ? 4 : 3);
        if (hasConsumer) {
            writeText(out, "consumer_id");
            writeText(out, b.consumerId().get());
        }
        writeText(out, "import_name"); writeText(out, b.importName());
        writeText(out, "provider_id"); writeText(out, b.providerId());
        writeText(out, "export_name"); writeText(out, b.exportName());
    }

    private static void writeSecretsArray(final ByteArrayOutputStream out,
                                          final List<PlanV1.SecretBinding> secrets) throws IOException {
        writeArrayHead(out, secrets.size());
        for (final PlanV1.SecretBinding s : secrets) {
            writeMapHead(out, 2);
            writeText(out, "secret_id");   writeText(out, s.secretId());
            writeText(out, "backend_uri"); writeText(out, s.backendUri());
        }
    }

    private static void writePolicy(final ByteArrayOutputStream out,
                                    final PlanV1.Policy p) throws IOException {
        // Policy carries the tenant Option in the middle of the struct;
        // #[serde(skip_serializing_if = Option::is_none)] omits it when
        // absent. Determinism / capabilities / limits are always present.
        final boolean hasTenant = p.tenant().isPresent();
        writeMapHead(out, hasTenant ? 4 : 3);
        writeText(out, "determinism"); writeText(out, p.determinism().wire());
        writeText(out, "capabilities"); writeCapabilitiesArray(out, p.capabilities());
        if (hasTenant) {
            writeText(out, "tenant");
            writeText(out, p.tenant().get());
        }
        writeText(out, "limits"); writeResourceLimits(out, p.limits());
    }

    private static void writeCapabilitiesArray(final ByteArrayOutputStream out,
                                               final List<PlanV1.Capability> caps) throws IOException {
        writeArrayHead(out, caps.size());
        for (final PlanV1.Capability c : caps) {
            writeMapHead(out, 2);
            writeText(out, "name");  writeText(out, c.name());
            writeText(out, "level"); writeText(out, c.level().wire());
        }
    }

    private static void writeResourceLimits(final ByteArrayOutputStream out,
                                            final PlanV1.ResourceLimits limits) throws IOException {
        int n = 0;
        if (limits.cpuMs().isPresent())       n++;
        if (limits.memoryBytes().isPresent()) n++;
        if (limits.ioOps().isPresent())       n++;
        writeMapHead(out, n);
        if (limits.cpuMs().isPresent()) {
            writeText(out, "cpu_ms");
            writeUnsigned(out, limits.cpuMs().get());
        }
        if (limits.memoryBytes().isPresent()) {
            writeText(out, "memory_bytes");
            writeUnsigned(out, limits.memoryBytes().get());
        }
        if (limits.ioOps().isPresent()) {
            writeText(out, "io_ops");
            writeUnsigned(out, limits.ioOps().get());
        }
    }

    private static void writeExplicitExportsArray(final ByteArrayOutputStream out,
                                                  final List<PlanV1.ExplicitExport> exports) throws IOException {
        writeArrayHead(out, exports.size());
        for (final PlanV1.ExplicitExport e : exports) {
            writeMapHead(out, 2);
            writeText(out, "source_instance"); writeText(out, e.sourceInstance());
            writeText(out, "interface_name");  writeText(out, e.interfaceName());
        }
    }

    // --- CBOR primitives ------------------------------------------------

    /** Write a CBOR text string (major type 3). */
    static void writeText(final ByteArrayOutputStream out, final String s) throws IOException {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeHead(out, MT_TEXT, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Write a CBOR byte string (major type 2). */
    static void writeBytes(final ByteArrayOutputStream out, final byte[] b) throws IOException {
        writeHead(out, MT_BYTES, b.length);
        out.write(b, 0, b.length);
    }

    /** Write a map header (major type 5) — {@code n} key/value pairs follow. */
    static void writeMapHead(final ByteArrayOutputStream out, final int n) throws IOException {
        writeHead(out, MT_MAP, n);
    }

    /** Write an array header (major type 4) — {@code n} items follow. */
    static void writeArrayHead(final ByteArrayOutputStream out, final int n) throws IOException {
        writeHead(out, MT_ARRAY, n);
    }

    /** Write an unsigned integer (major type 0). Long value must be ≥ 0. */
    static void writeUnsigned(final ByteArrayOutputStream out, final long v) throws IOException {
        if (v < 0) throw new IllegalArgumentException("writeUnsigned: negative value " + v);
        writeHead(out, MT_UINT, v);
    }

    /** Write a signed integer with correct major-type selection. */
    static void writeInteger(final ByteArrayOutputStream out, final long v) throws IOException {
        if (v >= 0) {
            writeHead(out, MT_UINT, v);
        } else {
            // CBOR negative integers are encoded as the head value = -1 - v.
            writeHead(out, MT_NEG_INT, -1L - v);
        }
    }

    /** Write a boolean as a CBOR simple value (major type 7). */
    static void writeBool(final ByteArrayOutputStream out, final boolean b) {
        out.write(mtByte(MT_SIMPLE, b ? SIMPLE_TRUE : SIMPLE_FALSE));
    }

    /** Write CBOR {@code null} (simple value 22, major type 7). */
    static void writeNull(final ByteArrayOutputStream out) {
        out.write(mtByte(MT_SIMPLE, SIMPLE_NULL));
    }

    /**
     * Write a CBOR head byte + length. Uses the shortest encoding that
     * fits the value — 5-bit inline for ≤ 23, then 1/2/4/8-byte extensions.
     */
    static void writeHead(final ByteArrayOutputStream out, final int majorType, final long value) throws IOException {
        if (value < 0) throw new IllegalArgumentException("head length must be ≥ 0: " + value);
        final int mt = (majorType & 0x07) << 5;
        if (value < 24L) {
            out.write(mt | (int) value);
        } else if (value <= 0xFFL) {
            out.write(mt | 24);
            out.write((int) (value & 0xFF));
        } else if (value <= 0xFFFFL) {
            out.write(mt | 25);
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) (value & 0xFF));
        } else if (value <= 0xFFFFFFFFL) {
            out.write(mt | 26);
            out.write((int) ((value >>> 24) & 0xFF));
            out.write((int) ((value >>> 16) & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) (value & 0xFF));
        } else {
            out.write(mt | 27);
            out.write((int) ((value >>> 56) & 0xFF));
            out.write((int) ((value >>> 48) & 0xFF));
            out.write((int) ((value >>> 40) & 0xFF));
            out.write((int) ((value >>> 32) & 0xFF));
            out.write((int) ((value >>> 24) & 0xFF));
            out.write((int) ((value >>> 16) & 0xFF));
            out.write((int) ((value >>> 8) & 0xFF));
            out.write((int) (value & 0xFF));
        }
    }

    private static int mtByte(final int majorType, final int shortValue) {
        return ((majorType & 0x07) << 5) | (shortValue & 0x1F);
    }

    // Package-private accessor so the tests can reach the primitive
    // writers without duplicating the internal-write helpers.
    static void writeOptionalText(final ByteArrayOutputStream out, final Optional<String> s) throws IOException {
        if (s.isPresent()) writeText(out, s.get()); else writeNull(out);
    }
}
