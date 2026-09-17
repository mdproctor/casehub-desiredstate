package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.eidos.org.api.OrganizationalUnit;

public record OrgUnitNodeSpec(OrganizationalUnit unit) implements NodeSpec {
    @Override public NodeType nodeType() { return OrgNodeTypes.UNIT; }
}
