package io.casehub.desiredstate.example.expansion;

import java.util.List;

public record ExpansionGoal(String locationId, List<String> requiredStructures,
                            DefensePosture defensePosture) {
    public ExpansionGoal {
        requiredStructures = List.copyOf(requiredStructures);
    }

    public ExpansionGoal withDefensePosture(DefensePosture posture) {
        return new ExpansionGoal(locationId, requiredStructures, posture);
    }
}
