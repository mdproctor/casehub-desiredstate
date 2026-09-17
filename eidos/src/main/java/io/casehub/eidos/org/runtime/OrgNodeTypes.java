package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeType;

public final class OrgNodeTypes {
    public static final NodeType UNIT = NodeType.of("org:unit");
    public static final NodeType RELATIONSHIP = NodeType.of("org:relationship");

    private OrgNodeTypes() {}
}
