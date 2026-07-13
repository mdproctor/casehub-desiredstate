package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record CbrProposal(
    String sourceId,
    CbrPath path,
    Set<NodeId> affectedNodeIds,
    Instant timestamp
) {
    public CbrProposal {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        affectedNodeIds = Set.copyOf(affectedNodeIds);
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
