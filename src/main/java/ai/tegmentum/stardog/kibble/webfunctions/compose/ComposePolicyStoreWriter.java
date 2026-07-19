package ai.tegmentum.stardog.kibble.webfunctions.compose;

import ai.tegmentum.stardog.kibble.webfunctions.WebFunctionConfig;
import com.complexible.common.base.Options;
import com.complexible.stardog.Kernel;
import com.complexible.stardog.db.DatabaseConnection;
import com.complexible.stardog.security.ShiroUtils;
import com.stardog.stark.IRI;
import com.stardog.stark.Literal;
import com.stardog.stark.Statement;
import com.stardog.stark.Value;
import com.stardog.stark.io.ParserOptions;
import com.stardog.stark.io.RDFFormats;
import com.stardog.stark.io.RDFParsers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Idempotent writer that projects a composition-plan Turtle document
 * (as produced by the orchestrator's {@code sys:compose/rdf#plan-to-turtle})
 * into the plugin's capability database under a dedicated named graph.
 *
 * <p>Named graph: {@link #COMPOSITIONS_NAMED_GRAPH} —
 * {@code <urn:stardog:webfunction:compositions>}. The graph is the
 * single point of truth for composition RDF so admin CONSTRUCT queries
 * against grants (which live in the default graph) and asks (which
 * live under {@link ai.tegmentum.stardog.kibble.webfunctions.CapabilityVocabulary#CAP_ASKS_NAMED_GRAPH})
 * never accidentally scan a composition triple set.
 *
 * <p>Idempotency: DELETE-then-INSERT keyed on the plan IRI. A repeat
 * insert of the same plan (same digest → same Turtle → same plan IRI)
 * overwrites the previous triples with byte-identical content — no
 * duplicates, no stale triples.
 *
 * <p>Failure policy: mirrors
 * {@link ai.tegmentum.stardog.kibble.webfunctions.KernelBackedCapabilityPolicyStore#recordAsk}
 * — SPARQL update failures log to stderr and swallow. A failed insert
 * is diagnostic loss, not a composition failure; the composed wasm
 * blob is already persisted by that point.
 */
public final class ComposePolicyStoreWriter {

    /** Named graph the composition RDF triples land under. */
    public static final String COMPOSITIONS_NAMED_GRAPH =
            "urn:stardog:webfunction:compositions";

    private final Kernel kernel;
    private final String databaseName;

    public ComposePolicyStoreWriter(final Kernel kernel) {
        this(kernel, WebFunctionConfig.capabilityPolicyDatabaseName());
    }

    public ComposePolicyStoreWriter(final Kernel kernel, final String databaseName) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
    }

    /**
     * Parse the given Turtle document, identify its plan subject IRI,
     * and idempotently DELETE-then-INSERT the resulting triples under
     * {@link #COMPOSITIONS_NAMED_GRAPH}. Best-effort — swallows and
     * logs failures.
     *
     * @param turtleBytes UTF-8 Turtle output of the orchestrator.
     * @param planIri     the plan subject IRI the writer keys the
     *                    DELETE clause on. Callers typically pass the
     *                    same IRI they handed to
     *                    {@code plan-to-turtle-with-iri}. When
     *                    {@code null}, uses
     *                    {@code urn:composition:plan} (the orchestrator's
     *                    default subject).
     */
    public void write(final byte[] turtleBytes, final String planIri) {
        Objects.requireNonNull(turtleBytes, "turtleBytes");
        final String subject = planIri == null || planIri.isEmpty()
                ? "urn:composition:plan"
                : planIri;
        final Set<Statement> statements;
        try (ByteArrayInputStream in = new ByteArrayInputStream(turtleBytes)) {
            statements = RDFParsers.read(in, RDFFormats.TURTLE,
                    ParserOptions.baseIRI(subject));
        } catch (IOException | RuntimeException parseFailure) {
            System.err.println("[wf-compose] composition-turtle parse failed for "
                    + subject + ": " + parseFailure.getMessage());
            return;
        }
        if (statements.isEmpty()) {
            // No triples to write — orchestrator produced an empty doc
            // (shouldn't happen in practice; log and no-op).
            System.err.println("[wf-compose] empty composition RDF for " + subject);
            return;
        }

        final String graph = "<" + escapeIri(COMPOSITIONS_NAMED_GRAPH) + ">";
        final String subjectIri = "<" + escapeIri(subject) + ">";

        // DELETE any prior triples anchored on the plan subject in the
        // compositions graph — idempotent overwrite semantics.
        final String delete =
                "DELETE { GRAPH " + graph + " { ?s ?p ?o } } "
                + "WHERE { GRAPH " + graph + " { "
                + subjectIri + " ?p ?o BIND(" + subjectIri + " AS ?s) "
                + "} }";

        final StringBuilder insert = new StringBuilder(
                "INSERT DATA { GRAPH " + graph + " { ");
        for (final Statement stmt : statements) {
            appendStatement(insert, stmt);
        }
        insert.append("} }");

        try {
            ShiroUtils.executeAsSuperUser(kernel.getSecurityManager(), () -> {
                try (DatabaseConnection conn = kernel.getConnection(databaseName, Options.empty())) {
                    conn.begin(UUID.randomUUID(), true);
                    try {
                        conn.update("", delete, null).execute();
                        conn.update("", insert.toString(), null).execute();
                        conn.commit();
                    } catch (RuntimeException ex) {
                        try { conn.rollback(); } catch (RuntimeException ignore) {}
                        throw ex;
                    }
                }
            });
        } catch (RuntimeException e) {
            System.err.println("[wf-compose] composition RDF insert failed for "
                    + subject + ": " + e.getMessage());
        }
    }

    /**
     * Serialize a single {@link Statement} into inline SPARQL
     * INSERT DATA form. Subject and predicate are always IRIs (Turtle
     * bnodes surface here as blank nodes — we skip; composition
     * triples from the orchestrator don't emit bnodes).
     */
    static void appendStatement(final StringBuilder out, final Statement stmt) {
        appendTerm(out, stmt.subject());
        out.append(' ');
        appendTerm(out, stmt.predicate());
        out.append(' ');
        appendObject(out, stmt.object());
        out.append(" . ");
    }

    private static void appendTerm(final StringBuilder out, final Value v) {
        if (v instanceof IRI) {
            out.append('<').append(escapeIri(v.toString())).append('>');
        } else {
            // A subject/predicate that isn't an IRI (bnode or literal)
            // is unexpected on composition triples; write it out as
            // best-effort so the DELETE half still narrows correctly.
            out.append(v.toString());
        }
    }

    private static void appendObject(final StringBuilder out, final Value v) {
        if (v instanceof IRI) {
            out.append('<').append(escapeIri(v.toString())).append('>');
            return;
        }
        if (v instanceof Literal) {
            final Literal lit = (Literal) v;
            out.append('"').append(escapeLiteral(lit.label())).append('"');
            final IRI dt = lit.datatypeIRI();
            if (dt != null
                    && !"http://www.w3.org/2001/XMLSchema#string".equals(dt.toString())
                    && !"http://www.w3.org/1999/02/22-rdf-syntax-ns#langString".equals(dt.toString())) {
                out.append("^^<").append(escapeIri(dt.toString())).append('>');
            }
            return;
        }
        // Blank node — emit its label as _:label.
        out.append(v.toString());
    }

    static String escapeIri(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c > 0x20 && c != '<' && c != '>' && c != '"'
                    && c != '{' && c != '}' && c != '|'
                    && c != '\\' && c != '^' && c != '`') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    static String escapeLiteral(final String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
