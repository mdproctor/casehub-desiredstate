package io.casehub.desiredstate.api;

@FunctionalInterface
public interface CompletionCondition {
    boolean isComplete(DesiredStateGraph desired, ActualState actual);

    static CompletionCondition allPresent() {
        return (desired, actual) -> desired.nodes().keySet().stream()
            .allMatch(id -> actual.statuses().getOrDefault(id, NodeStatus.UNKNOWN) == NodeStatus.PRESENT);
    }

    static CompletionCondition never() { return new Never(); }

    record Never() implements CompletionCondition {
        @Override
        public boolean isComplete(DesiredStateGraph desired, ActualState actual) { return false; }
    }
}
