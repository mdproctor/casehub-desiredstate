package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeType;

public final class ExpansionNodeTypes {
    public static final NodeType PROBE = new NodeType("probe");
    public static final NodeType NEXUS = new NodeType("nexus");
    public static final NodeType PYLON = new NodeType("pylon");
    public static final NodeType CANNON = new NodeType("cannon");
    public static final NodeType PATROL = new NodeType("patrol");
    public static final NodeType MONITOR = new NodeType("monitor");
    public static final NodeType RESPONSE = new NodeType("response");

    private ExpansionNodeTypes() {}
}
