package io.casehub.desiredstate.api;

import java.util.Objects;

public record OrderedStep(DesiredNode node, StepAction action) {
    public OrderedStep {
        Objects.requireNonNull(node, "OrderedStep.node must not be null");
        Objects.requireNonNull(action, "OrderedStep.action must not be null");
    }
}
