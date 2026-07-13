package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;

import java.util.List;

/**
 * Fault policy that rebuilds destroyed dungeon nodes.
 * Responds to NODE_DESTROYED faults by adding the destroyed node back to the graph.
 */
public class HeroRaidFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DESTROYED) {
            return List.of();
        }

        DesiredNode destroyedNode = current.nodes().get(event.node());
        if (destroyedNode == null) {
            return List.of();
        }

        return List.of(new GraphMutation.AddNode(destroyedNode));
    }
}
