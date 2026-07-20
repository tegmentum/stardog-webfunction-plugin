package ai.tegmentum.stardog.kibble.webfunctions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;

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

    @Before
    public void snapshotProperties() {
        // Snapshot only the keys under test so cross-test property
        // pollution can be reversed in @After without touching unrelated
        // system properties.
        priorPrefix = System.getProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX);
        System.clearProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX);
    }

    @After
    public void restoreProperties() {
        if (priorPrefix == null) {
            System.clearProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX);
        } else {
            System.setProperty(WebFunctionConfig.PROP_COMPOSE_ARTIFACT_URL_PREFIX, priorPrefix);
        }
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
}
