package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeType;

public final class SpatialNodeTypes {
    public static final NodeType CELL = new NodeType("spatial:cell");
    public static final NodeType UNIT = new NodeType("spatial:unit");
    public static final NodeType SCOUT = new NodeType("spatial:scout");
    public static final NodeType ZONE = new NodeType("spatial:zone");

    private SpatialNodeTypes() {}
}
