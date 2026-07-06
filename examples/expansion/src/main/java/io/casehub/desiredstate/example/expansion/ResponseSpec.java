package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeSpec;

public record ResponseSpec(String locationId, DefensePosture posture) implements NodeSpec {
}
