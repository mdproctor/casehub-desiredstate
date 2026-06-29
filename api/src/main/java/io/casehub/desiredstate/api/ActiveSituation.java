package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ActiveSituation(
        String situationId,
        double confidence,
        Map<String, Object> evidence,
        Instant since
) {
    public ActiveSituation {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(since, "since");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0–1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
