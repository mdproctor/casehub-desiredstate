package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Set;

public record ReconciliationResult(
    Set<NodeId> resolved, Set<NodeId> drifted, Set<NodeId> faulted,
    List<GraphMutation> mutations
) {
    public ReconciliationResult {
        resolved = Set.copyOf(resolved);
        drifted = Set.copyOf(drifted);
        faulted = Set.copyOf(faulted);
        mutations = List.copyOf(mutations);
    }
}
