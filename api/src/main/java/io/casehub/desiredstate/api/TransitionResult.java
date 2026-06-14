package io.casehub.desiredstate.api;

import java.util.Map;

public record TransitionResult(Map<NodeId, StepOutcome> outcomes) {
    public TransitionResult {
        outcomes = Map.copyOf(outcomes);
    }
}
