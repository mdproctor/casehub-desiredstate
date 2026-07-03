package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.ZoneSpec;
import java.util.List;

public class ZoneRebalanceFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        var node = current.nodes().get(event.node());
        if (node == null || !(node.spec() instanceof ZoneSpec)) {
            return List.of();
        }

        // The policy knows the zone is degraded but CANNOT determine which
        // child unit was lost. The FaultPolicy SPI receives:
        //   (FaultEvent, DesiredStateGraph)
        // but NOT ActualState. The FaultEvent carries the zone's NodeId
        // and a generic detail string — not enough to diagnose the specific failure.
        //
        // To redistribute, the policy would need to:
        // 1. Know which unit(s) are ABSENT — requires ActualState
        // 2. Recompute ratios excluding lost cells — requires zone structure knowledge
        // 3. Emit UpdateNode mutations for zone + all remaining units — N+1 mutations
        //
        // Even if it could do all this, the TransitionPlanner independently detects
        // the missing unit as ABSENT and schedules re-provisioning with the original
        // spec. The policy's redistribution and the planner's restoration would conflict.
        //
        // Returning empty to document the information gap.
        return List.of();
    }
}
