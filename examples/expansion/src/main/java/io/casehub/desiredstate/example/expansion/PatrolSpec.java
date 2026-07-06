package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeSpec;

public record PatrolSpec(String locationId) implements NodeSpec {
}
