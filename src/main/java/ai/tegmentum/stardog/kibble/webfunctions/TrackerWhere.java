package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wave B — WHERE-clause composition + operator whitelist for
 * {@link SqliteTrackerBackend}. Mirrors the Rust reference impl's
 * {@code compose_where} + {@code operator_is_supported} pair (see
 * {@code oxigraph-webfunction-plugin/crates/host-callbacks-impl/src/tracker_sink.rs}).
 *
 * <p><b>The operator whitelist is the second injection boundary.</b>
 * Anything not in the MVP set — {@code =}, {@code !=}, {@code <},
 * {@code >}, {@code <=}, {@code >=}, {@code LIKE}, {@code IS NULL},
 * {@code IS NOT NULL} — surfaces
 * {@link SqliteTrackerBackend.TrackerError.SchemaViolation} before the
 * fragment is interpolated. Operator strings are compared
 * case-insensitively but emitted uppercase in the SQL fragment so a
 * guest that writes {@code like} produces the same SQL as one that
 * writes {@code LIKE}.
 *
 * <p><b>Composition.</b> Multi-clause lists join with AND (implicit
 * conjunction; matches the WIT contract "compose by list
 * concatenation"). OR / NOT / nested groups are memo-§6 follow-on
 * work; a guest that needs OR issues multiple SELECTs and
 * deduplicates guest-side.
 */
public final class TrackerWhere {

    /** MVP operator whitelist — matches the WIT contract. All entries
     *  compared case-insensitively; emitted uppercase in SQL. */
    private static final java.util.Set<String> ALLOWED_OPERATORS = java.util.Set.of(
            "=", "!=", "<", ">", "<=", ">=", "LIKE", "IS NULL", "IS NOT NULL");

    /** Category of operator. VALUE — takes a bound value bound at a
     *  {@code ?} placeholder. NULLARY — {@code IS NULL} / {@code IS NOT NULL}
     *  emit no placeholder + no bound value. */
    public enum OperatorCategory { VALUE, NULLARY }

    /** Result of composing a WHERE list — the SQL fragment (empty
     *  {@link Optional} when the list was empty) plus the ordered list
     *  of {@code (column, value)} pairs the caller binds via
     *  {@link TrackerValueMarshaller#bind}. */
    public static final class Composed {
        public final Optional<String> fragment;
        public final List<Bound> params;

        Composed(final Optional<String> fragment, final List<Bound> params) {
            this.fragment = fragment;
            this.params = params;
        }
    }

    /** One parameter to bind at a WHERE {@code ?} placeholder, in
     *  emitted order. The column is resolved against the schema so the
     *  backend can call
     *  {@link TrackerValueMarshaller#bind(java.sql.PreparedStatement, int, TrackerSchema.ColumnDef, ComponentVal)}. */
    public static final class Bound {
        public final TrackerSchema.ColumnDef column;
        public final ComponentVal value;

        Bound(final TrackerSchema.ColumnDef column, final ComponentVal value) {
            this.column = column;
            this.value = value;
        }
    }

    /** One WHERE clause as parsed from a WIT {@code tracker-where}
     *  record. */
    public static final class Clause {
        public final String column;
        public final String operator;
        public final Optional<ComponentVal> value;

        public Clause(final String column,
                      final String operator,
                      final Optional<ComponentVal> value) {
            this.column = column;
            this.operator = operator;
            this.value = value;
        }

        /** Decode a WIT {@code tracker-where} record ComponentVal into
         *  this shape. */
        public static Clause fromWit(final ComponentVal record) {
            final java.util.Map<String, ComponentVal> fields = record.asRecord();
            final String column = fields.get("column").asString();
            final String operator = fields.get("operator").asString();
            final Optional<ComponentVal> value = fields.get("value").asSome();
            return new Clause(column, operator, value);
        }
    }

    private TrackerWhere() {}

    /** Whether the given operator is in the MVP whitelist. Case-
     *  insensitive. */
    public static boolean isOperatorSupported(final String op) {
        if (op == null) return false;
        return ALLOWED_OPERATORS.contains(op.toUpperCase(java.util.Locale.ROOT).trim());
    }

    /** Categorise an operator — value-taking or nullary. Rejects
     *  operators outside the whitelist via
     *  {@link SqliteTrackerBackend.TrackerError.SchemaViolation}. */
    public static OperatorCategory categorise(final String op) {
        if (!isOperatorSupported(op)) {
            throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                    "where-clause operator '" + op
                    + "' not in MVP whitelist (=, !=, <, >, <=, >=, LIKE, "
                    + "IS NULL, IS NOT NULL)");
        }
        final String u = op.toUpperCase(java.util.Locale.ROOT).trim();
        if ("IS NULL".equals(u) || "IS NOT NULL".equals(u)) {
            return OperatorCategory.NULLARY;
        }
        return OperatorCategory.VALUE;
    }

    /**
     * Compose a list of {@link Clause}s into a SQL fragment
     * (implicitly AND-joined) plus the ordered parameter-binding list.
     * An empty input list returns an empty {@link Optional} fragment so
     * the caller emits no {@code WHERE} keyword at all.
     *
     * <p>Every column named in a clause must be declared on
     * {@code schema}; every operator must be in the whitelist; every
     * value-taking operator must supply a value that
     * {@link TrackerValueMarshaller#validateValueAgainstColumn}
     * accepts. Nullary operators ({@code IS NULL} / {@code IS NOT NULL})
     * must NOT carry a value.
     */
    public static Composed compose(final TrackerSchema schema,
                                   final List<Clause> clauses) {
        if (clauses.isEmpty()) {
            return new Composed(Optional.empty(), List.of());
        }
        final StringBuilder sb = new StringBuilder(32 * clauses.size());
        final List<Bound> params = new ArrayList<>(clauses.size());
        for (int i = 0; i < clauses.size(); i++) {
            final Clause c = clauses.get(i);
            TrackerSchema.validateIdent(c.column);
            final TrackerSchema.ColumnDef col = schema.findColumn(c.column)
                    .orElseThrow(() -> new SqliteTrackerBackend.TrackerError.SchemaViolation(
                            "where-clause references column '" + c.column
                            + "' not in schema for table '" + schema.name + "'"));
            final OperatorCategory cat = categorise(c.operator);
            final String opUpper = c.operator.toUpperCase(java.util.Locale.ROOT).trim();
            if (i > 0) sb.append(" AND ");
            switch (cat) {
                case VALUE: {
                    final ComponentVal v = c.value.orElseThrow(() ->
                            new SqliteTrackerBackend.TrackerError.SchemaViolation(
                                    "where-clause operator '" + c.operator
                                    + "' requires a bound value on column '"
                                    + c.column + "'"));
                    TrackerValueMarshaller.validateValueAgainstColumn(col, v);
                    sb.append(c.column).append(' ').append(opUpper).append(" ?");
                    params.add(new Bound(col, v));
                    break;
                }
                case NULLARY: {
                    if (c.value.isPresent()) {
                        throw new SqliteTrackerBackend.TrackerError.SchemaViolation(
                                "where-clause operator '" + c.operator
                                + "' does not take a bound value (column '"
                                + c.column + "')");
                    }
                    sb.append(c.column).append(' ').append(opUpper);
                    break;
                }
            }
        }
        return new Composed(Optional.of(sb.toString()), params);
    }
}
