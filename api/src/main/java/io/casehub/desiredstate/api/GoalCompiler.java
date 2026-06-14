package io.casehub.desiredstate.api;

public interface GoalCompiler<G> {
    DesiredStateGraph compile(G goals, DesiredStateGraphFactory factory);
}
