package ai.tegmentum.stardog.kibble.webfunctions;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Extension-declared capability ask — the author's word for what
 * interfaces, methods, hosts, HTTP paths, and sub-invoked wasm callees
 * the extension expects to reach at runtime.
 *
 * <p>Populated by {@link CapabilityAskParser} from the Turtle payload
 * embedded in the {@code stardog.capability-ask} wasm custom section
 * (see {@code capability-ask.md} §§4 + 6). Consumed by:
 * <ul>
 *   <li>{@link KernelBackedCapabilityPolicyStore#recordAsk} —
 *       auto-inserts triples into the ask named graph on every load so
 *       the admin can diff ask vs grant with a SPARQL query
 *       ({@code capability-ask.md} §7).</li>
 *   <li>{@link HostCallbacks} enforcement path — every host-callback
 *       dispatch checks whether the invoked (interface, method) appears
 *       in the ask; if the ask is present and does not declare it, an
 *       audit row lands with outcome {@code GRANTED_UNDECLARED}
 *       ({@code capability-ask.md} §8).</li>
 * </ul>
 *
 * <p><strong>Untrusted input.</strong> The ask is the author's word,
 * never authorization ({@code capability-ask.md} §9). Grants remain the
 * sole trust source; this record is diagnostic + review UX only.
 *
 * <p>All collections are defensively copied to unmodifiable sets so a
 * mutation on the caller's side after construction cannot leak into
 * downstream diagnostics.
 *
 * @param asksInterfaces   Interface IRIs the extension asks the linker
 *                         to bind (e.g. {@code
 *                         "http://.../capability#GraphCallbacks"}).
 * @param asksMethods      Method IRIs (Interface_Method convention).
 * @param asksHosts        HTTP host strings — globs allowed on the
 *                         grant side.
 * @param asksHttpPaths    {@code host+path} prefix strings.
 * @param asksWasmCallees  Callee wasm URLs (any URL scheme).
 * @param rationale        Human-readable justification, when the author
 *                         provided one.
 */
public record CapabilityAsk(
        Set<String> asksInterfaces,
        Set<String> asksMethods,
        Set<String> asksHosts,
        Set<String> asksHttpPaths,
        Set<String> asksWasmCallees,
        Optional<String> rationale) {

    /** An ask with no declared items — used as the fallback when parsing yields nothing. */
    public static final CapabilityAsk EMPTY = new CapabilityAsk(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Optional.empty());

    public CapabilityAsk {
        Objects.requireNonNull(asksInterfaces,  "asksInterfaces");
        Objects.requireNonNull(asksMethods,     "asksMethods");
        Objects.requireNonNull(asksHosts,       "asksHosts");
        Objects.requireNonNull(asksHttpPaths,   "asksHttpPaths");
        Objects.requireNonNull(asksWasmCallees, "asksWasmCallees");
        Objects.requireNonNull(rationale,       "rationale");
        asksInterfaces  = Collections.unmodifiableSet(new LinkedHashSet<>(asksInterfaces));
        asksMethods     = Collections.unmodifiableSet(new LinkedHashSet<>(asksMethods));
        asksHosts       = Collections.unmodifiableSet(new LinkedHashSet<>(asksHosts));
        asksHttpPaths   = Collections.unmodifiableSet(new LinkedHashSet<>(asksHttpPaths));
        asksWasmCallees = Collections.unmodifiableSet(new LinkedHashSet<>(asksWasmCallees));
    }

    /**
     * True when the ask declares zero items across every axis and no
     * rationale. Callers that want to know "should we bother writing
     * this to the store" check this first.
     */
    public boolean isEmpty() {
        return asksInterfaces.isEmpty()
                && asksMethods.isEmpty()
                && asksHosts.isEmpty()
                && asksHttpPaths.isEmpty()
                && asksWasmCallees.isEmpty()
                && rationale.isEmpty();
    }

    /**
     * Whether the ask declares the given interface (matched by
     * wire-format name, e.g. {@code "graph-callbacks"} — or by IRI,
     * whichever the ask uses; the plugin stores both consistently via
     * {@link CapabilityAskParser}).
     *
     * <p>The warn-on-undeclared diagnostic
     * ({@code capability-ask.md} §8) consults this to decide whether a
     * granted dispatch merits an audit row with outcome
     * {@code GRANTED_UNDECLARED}.
     */
    public boolean declaresInterface(final String interfaceKey) {
        if (interfaceKey == null || interfaceKey.isEmpty()) return false;
        if (asksInterfaces.contains(interfaceKey)) return true;
        // Convenience: if the ask was captured as IRIs, allow a wire
        // name check by translating first.
        final String iri = CapabilityVocabulary.interfaceIriFor(interfaceKey);
        if (iri != null && asksInterfaces.contains(iri)) return true;
        // Reverse translation — ask carried the IRI, caller passed the
        // wire name via the enforcer path.
        for (final String asked : asksInterfaces) {
            final String wire = CapabilityVocabulary.wireNameFor(asked);
            if (interfaceKey.equals(wire)) return true;
        }
        return false;
    }

    /**
     * Whether the ask declares the given {@code (interface, method)}
     * tuple. Matches on either the IRI shape (e.g.
     * {@code "http://.../capability#GraphCallbacks_ExecuteQuery"}) or a
     * bare {@code interface/method} pair — the parser stores whatever
     * the Turtle carried, so the check is tolerant of both.
     */
    public boolean declaresMethod(final String interfaceWireName, final String method) {
        if (interfaceWireName == null || method == null) return false;
        // Bare form the author might write: "graph-callbacks/execute-query"
        final String barePair = interfaceWireName + "/" + method;
        if (asksMethods.contains(barePair)) return true;
        // Vocabulary-IRI form: Interface_Method under the cap namespace.
        // Convert wire to CamelCase interface + method for the underscore
        // pair the vocab prescribes.
        final String ifaceCamel = kebabToPascal(interfaceWireName);
        final String methodCamel = kebabToPascal(method);
        final String vocabPair = CapabilityVocabulary.NAMESPACE + ifaceCamel + "_" + methodCamel;
        return asksMethods.contains(vocabPair);
    }

    private static String kebabToPascal(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '-' || c == '_') { upper = true; continue; }
            if (upper) { out.append(Character.toUpperCase(c)); upper = false; }
            else { out.append(Character.toLowerCase(c)); }
        }
        return out.toString();
    }
}
