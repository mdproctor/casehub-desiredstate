package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates all registered fault policies against a fault event and merges their mutations.
 * Detects conflicts when multiple policies propose incompatible mutations for the same node.
 */
@ApplicationScoped
public class FaultPolicyEngine {

    private final List<FaultPolicy> policies;

    public FaultPolicyEngine(List<FaultPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public List<GraphMutation> evaluate(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        List<GraphMutation> allMutations = new ArrayList<>();
        for (FaultPolicy policy : policies) {
            List<GraphMutation> policyMutations = policy.onFault(tenancyId, event, current, actual);
            allMutations.addAll(policyMutations);
        }

        Map<NodeId, List<GraphMutation>> byNode              = new HashMap<>();
        List<GraphMutation>              dependencyMutations = new ArrayList<>();

        for (GraphMutation mutation : allMutations) {
            NodeId targetNodeId = getTargetNodeId(mutation);
            if (targetNodeId != null) {
                byNode.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(mutation);
            } else {
                dependencyMutations.add(mutation);
            }
        }

        List<GraphMutation> merged = new ArrayList<>();

        for (Map.Entry<NodeId, List<GraphMutation>> entry : byNode.entrySet()) {
            NodeId              nodeId        = entry.getKey();
            List<GraphMutation> nodeMutations = entry.getValue();

            Set<GraphMutation> uniqueMutations = new LinkedHashSet<>(nodeMutations);

            if (uniqueMutations.size() > 1) {
                Iterator<GraphMutation> it     = uniqueMutations.iterator();
                GraphMutation           first  = it.next();
                GraphMutation           second = it.next();
                throw new ConflictingMutationException(nodeId, first, second);
            }

            merged.addAll(uniqueMutations);
        }

        merged.addAll(dependencyMutations);

        return merged;
    }

    /**
     * Extracts the target NodeId from a mutation, or null for dependency mutations.
     */
    private NodeId getTargetNodeId(GraphMutation mutation) {return GraphDiff.targetNodeId(mutation);}
}
