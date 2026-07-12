package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RetrievedConfigurationTest {

    @Test
    void shouldRejectNullGraph() {
        assertThatThrownBy(() -> new RetrievedConfiguration(null, 0.9, "src-1", Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSourceId() {
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), 0.9, null, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectConfidenceOutOfRange() {
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), -0.1, "src-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), 1.1, "src-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievedConfiguration(emptyGraph(), Double.NaN, "src-1", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidConfiguration() {
        RetrievedConfiguration config = new RetrievedConfiguration(
            emptyGraph(), 0.85, "case-42", Map.of("reason", "provision-fix"));
        assertThat(config.confidence()).isEqualTo(0.85);
        assertThat(config.sourceId()).isEqualTo("case-42");
        assertThat(config.metadata()).containsEntry("reason", "provision-fix");
    }

    @Test
    void shouldAcceptBoundaryConfidenceValues() {
        assertThatNoException().isThrownBy(() -> new RetrievedConfiguration(emptyGraph(), 0.0, "s", Map.of()));
        assertThatNoException().isThrownBy(() -> new RetrievedConfiguration(emptyGraph(), 1.0, "s", Map.of()));
    }

    @Test
    void shouldDefensiveCopyMetadata() {
        HashMap<String, String> mutable = new HashMap<>();
        mutable.put("key", "val");
        RetrievedConfiguration config = new RetrievedConfiguration(emptyGraph(), 0.5, "s", mutable);
        mutable.put("extra", "bad");
        assertThat(config.metadata()).doesNotContainKey("extra");
    }

    @Test
    void shouldHandleNullMetadata() {
        RetrievedConfiguration config = new RetrievedConfiguration(emptyGraph(), 0.5, "s", null);
        assertThat(config.metadata()).isEmpty();
    }

    private static DesiredStateGraph emptyGraph() {
        return RetrievalContextTest.emptyGraph();
    }
}
