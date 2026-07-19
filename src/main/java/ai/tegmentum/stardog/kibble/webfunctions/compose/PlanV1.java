package ai.tegmentum.stardog.kibble.webfunctions.compose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Java mirror of the upstream {@code compose_core::types::PlanV1}
 * Rust struct (see
 * {@code libs/compose-core/src/types.rs} in the
 * webassembly-component-orchestration repo).
 *
 * <p>Immutable value type. Callers construct instances via
 * {@link Builder} and hand them to {@link PlanV1Cbor#encode(PlanV1)} to
 * produce the canonical CBOR payload the orchestrator's
 * {@code sys:compose/emit#compose} export accepts.
 *
 * <p>Fields mirror the Rust struct field order (which is what
 * {@code ciborium::into_writer} preserves via serde) so the resulting
 * CBOR byte stream is decodable by the orchestrator's serde-generated
 * {@code Deserialize} impl.
 */
public final class PlanV1 {

    /** Determinism mode enum — matches upstream serde-lowercase enum shape. */
    public enum DeterminismMode {
        STRICT("strict"),
        AUDIT("audit"),
        RELAXED("relaxed");

        private final String wire;
        DeterminismMode(final String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** Capability requirement level enum — lowercase wire form. */
    public enum CapabilityLevel {
        REQUIRED("required"),
        OPTIONAL("optional");

        private final String wire;
        CapabilityLevel(final String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** Linking strategy enum — lowercase wire form. */
    public enum Linkage {
        STATIC("static"),
        RUNTIME("runtime");

        private final String wire;
        Linkage(final String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public static final class ResourceLimits {
        private final Optional<Long> cpuMs;
        private final Optional<Long> memoryBytes;
        private final Optional<Long> ioOps;

        public ResourceLimits(final Optional<Long> cpuMs,
                              final Optional<Long> memoryBytes,
                              final Optional<Long> ioOps) {
            this.cpuMs = Objects.requireNonNull(cpuMs, "cpuMs");
            this.memoryBytes = Objects.requireNonNull(memoryBytes, "memoryBytes");
            this.ioOps = Objects.requireNonNull(ioOps, "ioOps");
        }

        public static ResourceLimits empty() {
            return new ResourceLimits(Optional.empty(), Optional.empty(), Optional.empty());
        }

        public Optional<Long> cpuMs()       { return cpuMs; }
        public Optional<Long> memoryBytes() { return memoryBytes; }
        public Optional<Long> ioOps()       { return ioOps; }
    }

    public static final class Capability {
        private final String name;
        private final CapabilityLevel level;

        public Capability(final String name, final CapabilityLevel level) {
            this.name = Objects.requireNonNull(name, "name");
            this.level = Objects.requireNonNull(level, "level");
        }

        public String name()             { return name; }
        public CapabilityLevel level()   { return level; }
    }

    public static final class Policy {
        private final DeterminismMode determinism;
        private final List<Capability> capabilities;
        private final Optional<String> tenant;
        private final ResourceLimits limits;

        public Policy(final DeterminismMode determinism,
                      final List<Capability> capabilities,
                      final Optional<String> tenant,
                      final ResourceLimits limits) {
            this.determinism = Objects.requireNonNull(determinism, "determinism");
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
            this.tenant = Objects.requireNonNull(tenant, "tenant");
            this.limits = Objects.requireNonNull(limits, "limits");
        }

        public static Policy defaults() {
            return new Policy(DeterminismMode.RELAXED,
                    Collections.emptyList(),
                    Optional.empty(),
                    ResourceLimits.empty());
        }

        public DeterminismMode determinism() { return determinism; }
        public List<Capability> capabilities() { return capabilities; }
        public Optional<String> tenant() { return tenant; }
        public ResourceLimits limits() { return limits; }
    }

    public static final class ComponentSpec {
        private final String id;
        private final byte[] digest;
        private final Optional<String> source;

        public ComponentSpec(final String id, final byte[] digest, final Optional<String> source) {
            this.id = Objects.requireNonNull(id, "id");
            this.digest = Objects.requireNonNull(digest, "digest").clone();
            this.source = Objects.requireNonNull(source, "source");
        }

        public String id() { return id; }
        public byte[] digest() { return digest.clone(); }
        public Optional<String> source() { return source; }
    }

    public static final class ImportBinding {
        private final Optional<String> consumerId;
        private final String importName;
        private final String providerId;
        private final String exportName;

        public ImportBinding(final Optional<String> consumerId,
                             final String importName,
                             final String providerId,
                             final String exportName) {
            this.consumerId = Objects.requireNonNull(consumerId, "consumerId");
            this.importName = Objects.requireNonNull(importName, "importName");
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            this.exportName = Objects.requireNonNull(exportName, "exportName");
        }

        public Optional<String> consumerId() { return consumerId; }
        public String importName() { return importName; }
        public String providerId() { return providerId; }
        public String exportName() { return exportName; }
    }

    public static final class SecretBinding {
        private final String secretId;
        private final String backendUri;

        public SecretBinding(final String secretId, final String backendUri) {
            this.secretId = Objects.requireNonNull(secretId, "secretId");
            this.backendUri = Objects.requireNonNull(backendUri, "backendUri");
        }

        public String secretId() { return secretId; }
        public String backendUri() { return backendUri; }
    }

    public static final class ExplicitExport {
        private final String sourceInstance;
        private final String interfaceName;

        public ExplicitExport(final String sourceInstance, final String interfaceName) {
            this.sourceInstance = Objects.requireNonNull(sourceInstance, "sourceInstance");
            this.interfaceName = Objects.requireNonNull(interfaceName, "interfaceName");
        }

        public String sourceInstance() { return sourceInstance; }
        public String interfaceName()  { return interfaceName; }
    }

    // --- PlanV1 fields (order matches the Rust struct declaration order,
    //     which serde preserves through ciborium::into_writer). ------------

    private final String version;
    private final String root;
    private final List<ComponentSpec> components;
    private final List<ImportBinding> bindings;
    private final List<SecretBinding> secrets;
    private final Policy policy;
    private final Linkage linkage;
    private final List<ExplicitExport> explicitExports;

    public PlanV1(final String version,
                  final String root,
                  final List<ComponentSpec> components,
                  final List<ImportBinding> bindings,
                  final List<SecretBinding> secrets,
                  final Policy policy,
                  final Linkage linkage,
                  final List<ExplicitExport> explicitExports) {
        this.version = Objects.requireNonNull(version, "version");
        this.root = Objects.requireNonNull(root, "root");
        this.components = Collections.unmodifiableList(new ArrayList<>(components));
        this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
        this.secrets = Collections.unmodifiableList(new ArrayList<>(secrets));
        this.policy = Objects.requireNonNull(policy, "policy");
        this.linkage = Objects.requireNonNull(linkage, "linkage");
        this.explicitExports = Collections.unmodifiableList(new ArrayList<>(explicitExports));
    }

    public String version()                      { return version; }
    public String root()                         { return root; }
    public List<ComponentSpec> components()      { return components; }
    public List<ImportBinding> bindings()        { return bindings; }
    public List<SecretBinding> secrets()         { return secrets; }
    public Policy policy()                       { return policy; }
    public Linkage linkage()                     { return linkage; }
    public List<ExplicitExport> explicitExports(){ return explicitExports; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String version = "v1";
        private String root;
        private final List<ComponentSpec> components = new ArrayList<>();
        private final List<ImportBinding> bindings = new ArrayList<>();
        private final List<SecretBinding> secrets = new ArrayList<>();
        private Policy policy = Policy.defaults();
        private Linkage linkage = Linkage.STATIC;
        private final List<ExplicitExport> explicitExports = new ArrayList<>();

        public Builder version(final String v) { this.version = v; return this; }
        public Builder root(final String r) { this.root = r; return this; }
        public Builder addComponent(final ComponentSpec c) { this.components.add(c); return this; }
        public Builder addBinding(final ImportBinding b) { this.bindings.add(b); return this; }
        public Builder addSecret(final SecretBinding s) { this.secrets.add(s); return this; }
        public Builder policy(final Policy p) { this.policy = p; return this; }
        public Builder linkage(final Linkage l) { this.linkage = l; return this; }
        public Builder addExplicitExport(final ExplicitExport e) { this.explicitExports.add(e); return this; }

        public PlanV1 build() {
            return new PlanV1(version, Objects.requireNonNull(root, "root"),
                    components, bindings, secrets, policy, linkage, explicitExports);
        }
    }
}
