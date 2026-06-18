package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.DesiredNode;

import java.util.List;
import java.util.stream.Collectors;

public class AmbiguousBackendException extends RuntimeException {

    private final DesiredNode node;
    private final List<ExecutionBackend> matchingBackends;

    public AmbiguousBackendException(DesiredNode node, List<ExecutionBackend> matchingBackends) {
        super("Multiple execution backends match node " + node.id().value()
                + " (type: " + node.type().value() + "): "
                + matchingBackends.stream()
                    .map(b -> b.getClass().getSimpleName())
                    .collect(Collectors.joining(", ")));
        this.node = node;
        this.matchingBackends = List.copyOf(matchingBackends);
    }

    public DesiredNode node() {
        return node;
    }

    public List<ExecutionBackend> matchingBackends() {
        return matchingBackends;
    }
}
