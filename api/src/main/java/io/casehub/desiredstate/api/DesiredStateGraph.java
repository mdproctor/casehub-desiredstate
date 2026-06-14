package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Set;

public interface DesiredStateGraph {
    Map<NodeId, DesiredNode> nodes();
    Set<Dependency> dependencies();
    Set<NodeId> dependenciesOf(NodeId node);
    Set<NodeId> dependentsOf(NodeId node);
    Set<NodeId> roots();
    Set<NodeId> leaves();
    int version();
    boolean isEmpty();
    DesiredStateGraph withNode(DesiredNode node);
    DesiredStateGraph withoutNode(NodeId id);
    DesiredStateGraph withDependency(Dependency dep);
    DesiredStateGraph withoutDependency(Dependency dep);
    DesiredStateGraph withMutation(GraphMutation mutation);
    DesiredStateGraph overlay(DesiredStateGraph other);
    DesiredStateGraph connect(DesiredStateGraph other);
}
