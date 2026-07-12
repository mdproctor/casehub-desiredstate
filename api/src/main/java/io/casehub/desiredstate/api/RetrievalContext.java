package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;

import java.util.Objects;

public record RetrievalContext(
    DesiredStateGraph currentGraph,
    ActualState actualState,
    FaultEvent faultEvent,
    ActiveSituation situation
) {
    public RetrievalContext {
        Objects.requireNonNull(currentGraph, "currentGraph must not be null");
        Objects.requireNonNull(actualState, "actualState must not be null");
    }

    public static RetrievalContext forFault(DesiredStateGraph graph, ActualState actual,
                                            FaultEvent event) {
        Objects.requireNonNull(event, "faultEvent must not be null");
        return new RetrievalContext(graph, actual, event, null);
    }

    public static RetrievalContext forSituation(DesiredStateGraph graph, ActualState actual,
                                                 ActiveSituation situation) {
        Objects.requireNonNull(situation, "situation must not be null");
        return new RetrievalContext(graph, actual, null, situation);
    }
}
