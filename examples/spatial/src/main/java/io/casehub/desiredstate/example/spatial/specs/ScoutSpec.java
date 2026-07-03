package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record ScoutSpec(NodeId cellId, int visionRange) implements NodeSpec {}
