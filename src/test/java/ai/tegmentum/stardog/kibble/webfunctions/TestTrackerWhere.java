package ai.tegmentum.stardog.kibble.webfunctions;

import ai.tegmentum.wasmtime4j.component.ComponentVal;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Wave B unit tests for {@link TrackerWhere}. Covers the operator
 * whitelist, composition into AND-joined fragments, and parameter
 * emission ordering.
 */
public class TestTrackerWhere {

    private static TrackerSchema schema() {
        return new TrackerSchema("aliases", List.of(
                new TrackerSchema.ColumnDef("alias", TrackerSchema.ColumnType.TEXT, true, false),
                new TrackerSchema.ColumnDef("canonical", TrackerSchema.ColumnType.TEXT, false, false),
                new TrackerSchema.ColumnDef("updated_at", TrackerSchema.ColumnType.INTEGER, false, true)));
    }

    // ---- operator whitelist -----------------------------------------

    @Test
    public void mvpOperatorsAllAccepted() {
        for (final String op : List.of("=", "!=", "<", ">", "<=", ">=",
                "LIKE", "like", "Like", "IS NULL", "is null", "IS NOT NULL")) {
            assertThat(TrackerWhere.isOperatorSupported(op))
                    .as("op should be supported: %s", op).isTrue();
        }
    }

    @Test
    public void unsafeOperatorsRejected() {
        final String[] hostile = {
                "; DROP TABLE users --",
                "UNION SELECT",
                "AND 1=1",
                "OR 1=1",
                "==",           // not SQLite dialect
                "<>",           // not in MVP whitelist
                "GLOB",         // SQLite dialect but not in MVP set
                "REGEXP",
                "NOT IN",
                "BETWEEN",
                "",
        };
        for (final String bad : hostile) {
            assertThat(TrackerWhere.isOperatorSupported(bad))
                    .as("must reject: %s", bad).isFalse();
        }
    }

    @Test
    public void categoriseValueOperators() {
        for (final String op : List.of("=", "!=", "<", ">", "<=", ">=", "LIKE")) {
            assertThat(TrackerWhere.categorise(op))
                    .isEqualTo(TrackerWhere.OperatorCategory.VALUE);
        }
    }

    @Test
    public void categoriseNullaryOperators() {
        assertThat(TrackerWhere.categorise("IS NULL"))
                .isEqualTo(TrackerWhere.OperatorCategory.NULLARY);
        assertThat(TrackerWhere.categorise("is not null"))
                .isEqualTo(TrackerWhere.OperatorCategory.NULLARY);
    }

    @Test
    public void categoriseRejectsUnsafeOperator() {
        assertThat(catchThrowable(() -> TrackerWhere.categorise("UNSAFE_OP")))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("UNSAFE_OP");
    }

    // ---- compose: empty ---------------------------------------------

    @Test
    public void composeEmptyReturnsEmptyFragment() {
        final TrackerWhere.Composed out = TrackerWhere.compose(schema(), List.of());
        assertThat(out.fragment).isEmpty();
        assertThat(out.params).isEmpty();
    }

    // ---- compose: single clause -------------------------------------

    @Test
    public void composeSingleValueClauseEmitsPlaceholder() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("alias", "=",
                Optional.of(ComponentVal.variant("text-value", ComponentVal.string("x"))));
        final TrackerWhere.Composed out = TrackerWhere.compose(schema(), List.of(c));
        assertThat(out.fragment).contains("alias = ?");
        assertThat(out.params).hasSize(1);
        assertThat(out.params.get(0).column.name).isEqualTo("alias");
    }

    @Test
    public void composeIsNullEmitsNoPlaceholder() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("updated_at", "IS NULL",
                Optional.empty());
        final TrackerWhere.Composed out = TrackerWhere.compose(schema(), List.of(c));
        assertThat(out.fragment).contains("updated_at IS NULL");
        assertThat(out.fragment.get()).doesNotContain("?");
        assertThat(out.params).isEmpty();
    }

    @Test
    public void composeIsNotNullEmitsUppercasedOperator() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("updated_at", "is not null",
                Optional.empty());
        final TrackerWhere.Composed out = TrackerWhere.compose(schema(), List.of(c));
        assertThat(out.fragment).contains("updated_at IS NOT NULL");
    }

    // ---- compose: multi-clause AND ----------------------------------

    @Test
    public void composeMultipleClausesJoinsWithAnd() {
        final TrackerWhere.Clause a = new TrackerWhere.Clause("alias", "LIKE",
                Optional.of(ComponentVal.variant("text-value", ComponentVal.string("http:%"))));
        final TrackerWhere.Clause b = new TrackerWhere.Clause("updated_at", ">",
                Optional.of(ComponentVal.variant("integer-value", ComponentVal.s64(100L))));
        final TrackerWhere.Clause c = new TrackerWhere.Clause("canonical", "IS NOT NULL",
                Optional.empty());
        final TrackerWhere.Composed out = TrackerWhere.compose(schema(),
                List.of(a, b, c));
        // Preserves order and joins with AND.
        assertThat(out.fragment).contains(
                "alias LIKE ? AND updated_at > ? AND canonical IS NOT NULL");
        assertThat(out.params).hasSize(2); // nullary clause contributes no param
        assertThat(out.params.get(0).column.name).isEqualTo("alias");
        assertThat(out.params.get(1).column.name).isEqualTo("updated_at");
    }

    // ---- compose: error cases ---------------------------------------

    @Test
    public void composeUnknownColumnRejected() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("nope", "=",
                Optional.of(ComponentVal.variant("text-value", ComponentVal.string("x"))));
        assertThat(catchThrowable(() -> TrackerWhere.compose(schema(), List.of(c))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("nope");
    }

    @Test
    public void composeUnsafeOperatorRejected() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("alias",
                "; DROP TABLE users --",
                Optional.of(ComponentVal.variant("text-value", ComponentVal.string("x"))));
        assertThat(catchThrowable(() -> TrackerWhere.compose(schema(), List.of(c))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void composeInjectionShapedColumnRejected() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("alias; DROP TABLE t", "=",
                Optional.of(ComponentVal.variant("text-value", ComponentVal.string("x"))));
        assertThat(catchThrowable(() -> TrackerWhere.compose(schema(), List.of(c))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class);
    }

    @Test
    public void composeValueOperatorMissingValueRejected() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("alias", "=", Optional.empty());
        assertThat(catchThrowable(() -> TrackerWhere.compose(schema(), List.of(c))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("requires a bound value");
    }

    @Test
    public void composeNullaryOperatorWithValueRejected() {
        final TrackerWhere.Clause c = new TrackerWhere.Clause("updated_at", "IS NULL",
                Optional.of(ComponentVal.variant("integer-value", ComponentVal.s64(1L))));
        assertThat(catchThrowable(() -> TrackerWhere.compose(schema(), List.of(c))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("does not take a bound value");
    }

    @Test
    public void composeTypeMismatchRejected() {
        // Bind an integer value at a text column via WHERE.
        final TrackerWhere.Clause c = new TrackerWhere.Clause("alias", "=",
                Optional.of(ComponentVal.variant("integer-value", ComponentVal.s64(1L))));
        assertThat(catchThrowable(() -> TrackerWhere.compose(schema(), List.of(c))))
                .isInstanceOf(SqliteTrackerBackend.TrackerError.SchemaViolation.class)
                .hasMessageContaining("type mismatch");
    }
}
