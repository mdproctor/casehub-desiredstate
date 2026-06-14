package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;

public record TransitionPlan(
    List<OrderedStep> removals, List<OrderedStep> additions,
    DesiredStateGraph before, DesiredStateGraph after
) {
    public TransitionPlan {
        removals = List.copyOf(removals);
        additions = List.copyOf(additions);
        Objects.requireNonNull(before, "TransitionPlan.before must not be null");
        Objects.requireNonNull(after, "TransitionPlan.after must not be null");
    }

    public boolean isEmpty() {
        return removals.isEmpty() && additions.isEmpty();
    }
}
