package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CbrOutcomeData(
    String tenancyId,
    String sourceId,
    CbrPath path,
    Map<String, String> nodeOutcomes,
    int successCount,
    int failureCount,
    int resolvedCount,
    double successRate,
    Instant proposedAt,
    Instant observedAt
) {
    public CbrOutcomeData {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        nodeOutcomes = Map.copyOf(nodeOutcomes);
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
