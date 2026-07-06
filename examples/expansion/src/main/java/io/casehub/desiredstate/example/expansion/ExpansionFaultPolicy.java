package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import java.util.List;

public class ExpansionFaultPolicy implements FaultPolicy {
    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        // Simple retry — no graph mutations. Persistent faults escalate via RAS.
        return List.of();
    }
}
