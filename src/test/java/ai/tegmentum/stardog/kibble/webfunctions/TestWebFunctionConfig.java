package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebFunctionConfig} getters that read directly
 * from system properties. Scope kept narrow — one getter per test — so
 * failures point at the exact key that regressed.
 *
 * <p>Currently exercises the compose artifact-URL prefix key
 * ({@link WebFunctionConfig#PROP_COMPOSE_ARTIFACT_URL_PREFIX}). Extend
 * with new getters as they land — the property save/restore fixture
 * below is reusable.
 */
public class TestWebFunctionConfig {

    private String priorPrefix;
    private String priorEpochTickMillis;
    private String priorMaxExecMillis;

    @Before
    public void snapshotProperties() {
        // Snapshot only the keys under test so cross-test property
        // pollution can be reversed in @After without touching unrelated
        // system properties.
        priorPrefix = System.getProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX);
        priorEpochTickMillis = System.getProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS);
        priorMaxExecMillis   = System.getProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
        System.clearProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX);
        System.clearProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS);
        System.clearProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS);
    }

    @After
    public void restoreProperties() {
        restore(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX, priorPrefix);
        restore(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS,           priorEpochTickMillis);
        restore(WebFunctionConfig.PROP_MAX_EXEC_MILLIS,             priorMaxExecMillis);
    }

    private static void restore(final String key, final String prior) {
        if (prior == null) System.clearProperty(key);
        else               System.setProperty(key, prior);
    }

    @Test
    public void artifactUrlPrefixUnsetReturnsEmpty() {
        // @Before clears the property — this observes the unset state.
        assertThat(WebFunctionConfig.getArtifactUrlPrefix()).isEqualTo(Optional.empty());
    }

    @Test
    public void artifactUrlPrefixSetReturnsValue() {
        System.setProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX,
                "https://cdn.example.com/artifacts/");
        assertThat(WebFunctionConfig.getArtifactUrlPrefix())
                .contains("https://cdn.example.com/artifacts/");
    }

    @Test
    public void artifactUrlPrefixWhitespaceIsTrimmed() {
        System.setProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX,
                "  https://cdn.example.com/artifacts/  ");
        assertThat(WebFunctionConfig.getArtifactUrlPrefix())
                .contains("https://cdn.example.com/artifacts/");
    }

    @Test
    public void artifactUrlPrefixEmptyStringReturnsEmpty() {
        System.setProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX, "");
        assertThat(WebFunctionConfig.getArtifactUrlPrefix()).isEqualTo(Optional.empty());
    }

    @Test
    public void artifactUrlPrefixWhitespaceOnlyReturnsEmpty() {
        System.setProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX, "   \t  ");
        assertThat(WebFunctionConfig.getArtifactUrlPrefix()).isEqualTo(Optional.empty());
    }

    // --- Task 303 T5 — epoch interruption config ---

    @Test
    public void epochTickMillisUnsetReturnsDefault() {
        assertThat(WebFunctionConfig.epochTickMillis())
                .isEqualTo(WebFunctionConfig.DEFAULT_EPOCH_TICK_MILLIS);
    }

    @Test
    public void epochTickMillisHonoredWhenSet() {
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "25");
        assertThat(WebFunctionConfig.epochTickMillis()).isEqualTo(25L);
    }

    @Test
    public void epochTickMillisNonPositiveFallsBackToDefault() {
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "0");
        assertThat(WebFunctionConfig.epochTickMillis())
                .isEqualTo(WebFunctionConfig.DEFAULT_EPOCH_TICK_MILLIS);
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "-1");
        assertThat(WebFunctionConfig.epochTickMillis())
                .isEqualTo(WebFunctionConfig.DEFAULT_EPOCH_TICK_MILLIS);
    }

    /** No PROP_MAX_EXEC_MILLIS → no deadline ticks (opt-in via existing config key). */
    @Test
    public void epochDeadlineTicksAbsentWhenExecMaxUnset() {
        assertThat(WebFunctionConfig.epochDeadlineTicks()).isEqualTo(OptionalLong.empty());
    }

    /** ceil(exec / tick) — 5000 / 100 = 50 exactly. */
    @Test
    public void epochDeadlineTicksExactDivision() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "5000");
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "100");
        assertThat(WebFunctionConfig.epochDeadlineTicks()).isEqualTo(OptionalLong.of(50L));
    }

    /** ceil(exec / tick) — 5001 / 100 = 51 (rounded up). */
    @Test
    public void epochDeadlineTicksCeiling() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "5001");
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "100");
        assertThat(WebFunctionConfig.epochDeadlineTicks()).isEqualTo(OptionalLong.of(51L));
    }

    /** Exec less than one tick still yields at least 1 tick of grace. */
    @Test
    public void epochDeadlineTicksAtLeastOne() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "1");
        System.setProperty(WebFunctionConfig.PROP_EPOCH_TICK_MILLIS, "100");
        assertThat(WebFunctionConfig.epochDeadlineTicks()).isEqualTo(OptionalLong.of(1L));
    }

    /** Exec = 0 is treated as "no cap" — no ticks. */
    @Test
    public void epochDeadlineTicksZeroExecIsAbsent() {
        System.setProperty(WebFunctionConfig.PROP_MAX_EXEC_MILLIS, "0");
        assertThat(WebFunctionConfig.epochDeadlineTicks()).isEqualTo(OptionalLong.empty());
    }
}
