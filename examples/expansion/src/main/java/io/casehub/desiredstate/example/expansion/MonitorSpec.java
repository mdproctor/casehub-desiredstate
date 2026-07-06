package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeSpec;

public record MonitorSpec(String locationId) implements NodeSpec {
}
