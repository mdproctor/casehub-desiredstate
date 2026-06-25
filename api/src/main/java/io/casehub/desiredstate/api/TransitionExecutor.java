package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Uni;

public interface TransitionExecutor {
    Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId);
}
