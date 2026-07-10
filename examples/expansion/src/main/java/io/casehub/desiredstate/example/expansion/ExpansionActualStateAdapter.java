package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads the actual state of the expansion world from {@link ExpansionWorld}.
 * Translates world-level structure states into the generic {@link ActualState}
 * snapshot the reconciliation loop uses.
 */
public class ExpansionActualStateAdapter implements ActualStateAdapter {

    private final ExpansionWorld world;

    public ExpansionActualStateAdapter(ExpansionWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
            ExpansionNodeTypes.PROBE, ExpansionNodeTypes.NEXUS,
            ExpansionNodeTypes.PYLON, ExpansionNodeTypes.CANNON,
            ExpansionNodeTypes.PATROL, ExpansionNodeTypes.MONITOR,
            ExpansionNodeTypes.RESPONSE
        );
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();

        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeId id = entry.getKey();
            ExpansionWorld.StructureState state = world.state(id);

            if (state == null || state == ExpansionWorld.StructureState.DESTROYED || state == ExpansionWorld.StructureState.BUILDING) {
                statuses.put(id, NodeStatus.ABSENT);
            } else {
                // BUILT, PATROLLING, MONITORING, RESPONDING -> PRESENT
                statuses.put(id, NodeStatus.PRESENT);
            }
        }

        return new ActualState(statuses);
    }
}
