package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.eidos.org.api.AgentRelationship;
import io.casehub.eidos.org.api.OrganizationalUnit;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class OrgGoalCompiler implements GoalCompiler<OrgGoalCompiler.OrgGoal> {

    public record OrgGoal(
        List<OrganizationalUnit> units,
        List<AgentRelationship> relationships
    ) {
        public OrgGoal {
            units = units != null ? List.copyOf(units) : List.of();
            relationships = relationships != null ? List.copyOf(relationships) : List.of();
        }
    }

    @Override
    public CompilationResult compile(OrgGoal goal, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        for (var unit : goal.units()) {
            var nodeId = NodeId.of(unit.unitId());
            nodes.add(new DesiredNode(nodeId, new OrgUnitNodeSpec(unit), HumanGating.NONE));
            if (unit.parentUnitId() != null) {
                deps.add(new Dependency(nodeId, NodeId.of(unit.parentUnitId())));
            }
        }

        for (var rel : goal.relationships()) {
            var nodeId = NodeId.of(relationshipNodeId(rel));
            nodes.add(new DesiredNode(nodeId, new OrgRelationshipNodeSpec(rel), HumanGating.NONE));
        }

        return CompilationResult.single(factory.of(nodes, deps));
    }

    static String relationshipNodeId(AgentRelationship rel) {
        return "rel:" + rel.sourceAgentId() + ":" + rel.targetAgentId() + ":" + rel.kind().name();
    }
}
