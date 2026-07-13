package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CbrOutcomeDataTest {

    @Test
    void validOutcome() {
        var outcome = new CbrOutcomeData("t1", "src-1", CbrPath.FAULT,
            Map.of("n1", "SUCCEEDED"), 1, 0, 1, 1.0,
            Instant.now(), Instant.now());
        assertThat(outcome.tenancyId()).isEqualTo("t1");
        assertThat(outcome.successRate()).isEqualTo(1.0);
        assertThat(outcome.resolvedCount()).isEqualTo(1);
    }

    @Test
    void nodeOutcomes_defensiveCopy() {
        var mutable = new HashMap<>(Map.of("n1", "SUCCEEDED"));
        var outcome = new CbrOutcomeData("t1", "src-1", CbrPath.FAULT,
            mutable, 1, 0, 1, 1.0, Instant.now(), Instant.now());
        mutable.put("n2", "FAILED");
        assertThat(outcome.nodeOutcomes()).hasSize(1);
    }

    @Test
    void nullTenancyId_throws() {
        assertThatThrownBy(() -> new CbrOutcomeData(null, "src-1", CbrPath.FAULT,
            Map.of(), 0, 0, 0, 0.0, Instant.now(), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSourceId_throws() {
        assertThatThrownBy(() -> new CbrOutcomeData("t1", null, CbrPath.FAULT,
            Map.of(), 0, 0, 0, 0.0, Instant.now(), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullPath_throws() {
        assertThatThrownBy(() -> new CbrOutcomeData("t1", "src-1", null,
            Map.of(), 0, 0, 0, 0.0, Instant.now(), Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullProposedAt_throws() {
        assertThatThrownBy(() -> new CbrOutcomeData("t1", "src-1", CbrPath.FAULT,
            Map.of(), 0, 0, 0, 0.0, null, Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullObservedAt_throws() {
        assertThatThrownBy(() -> new CbrOutcomeData("t1", "src-1", CbrPath.FAULT,
            Map.of(), 0, 0, 0, 0.0, Instant.now(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
