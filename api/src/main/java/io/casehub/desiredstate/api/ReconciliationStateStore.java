package io.casehub.desiredstate.api;

import java.util.Optional;

public interface ReconciliationStateStore {
    void store(String tenancyId, DesiredStateGraph lastReconciledDesired);
    Optional<DesiredStateGraph> load(String tenancyId);
    void remove(String tenancyId);
}
