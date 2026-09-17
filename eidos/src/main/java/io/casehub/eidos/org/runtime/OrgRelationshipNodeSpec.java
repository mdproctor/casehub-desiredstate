package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.eidos.org.api.AgentRelationship;

public record OrgRelationshipNodeSpec(AgentRelationship relationship) implements NodeSpec {
    @Override public NodeType nodeType() { return OrgNodeTypes.RELATIONSHIP; }
}
