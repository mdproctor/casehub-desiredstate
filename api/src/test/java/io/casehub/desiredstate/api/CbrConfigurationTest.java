package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CbrConfigurationTest {

    @Test
    void shouldRejectNegativeRetrievalConfidence() {
        assertThatThrownBy(() -> new CbrConfiguration(-0.1, 0.6, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectRetrievalConfidenceAboveOne() {
        assertThatThrownBy(() -> new CbrConfiguration(1.1, 0.6, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNaNRetrievalConfidence() {
        assertThatThrownBy(() -> new CbrConfiguration(Double.NaN, 0.6, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeAdaptationConfidence() {
        assertThatThrownBy(() -> new CbrConfiguration(0.5, -0.1, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveMaxCandidates() {
        assertThatThrownBy(() -> new CbrConfiguration(0.5, 0.6, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CbrConfiguration(0.5, 0.6, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptValidConfiguration() {
        CbrConfiguration config = new CbrConfiguration(0.5, 0.6, 3);
        assertThat(config.minimumRetrievalConfidence()).isEqualTo(0.5);
        assertThat(config.minimumAdaptationConfidence()).isEqualTo(0.6);
        assertThat(config.maxCandidates()).isEqualTo(3);
    }

    @Test
    void shouldAcceptBoundaryValues() {
        assertThatNoException().isThrownBy(() -> new CbrConfiguration(0.0, 0.0, 1));
        assertThatNoException().isThrownBy(() -> new CbrConfiguration(1.0, 1.0, 1));
    }
}
