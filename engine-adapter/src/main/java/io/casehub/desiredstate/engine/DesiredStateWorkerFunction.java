package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Objects;

/**
 * CDI bean that wraps {@link NodeProvisioner} as a casehub-engine worker function.
 * <p>
 * Takes a {@code Map<String,Object>} input containing {@code nodeId}, {@code nodeType},
 * and {@code action}, resolves the target node from the graph, and dispatches to the
 * appropriate provision/deprovision method.
 */
@ApplicationScoped
public class DesiredStateWorkerFunction {

    private final NodeProvisioner provisioner;

    public DesiredStateWorkerFunction(NodeProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /**
     * Execute a provisioning action for a single node.
     *
     * @param input     map with keys: nodeId (String), action (String: PROVISION|DEPROVISION)
     * @param graph     the desired-state graph containing the target node
     * @param tenancyId the tenancy identifier for context
     * @return result map with keys: nodeId, action, status, reason (if failed)
     * @throws IllegalArgumentException if required keys are missing or node not found
     */
    public Map<String, Object> execute(Map<String, Object> input, DesiredStateGraph graph, String tenancyId) {
        String nodeIdStr = requireString(input, "nodeId");
        String actionStr = requireString(input, "action");

        NodeId nodeId = NodeId.of(nodeIdStr);
        StepAction action = StepAction.valueOf(actionStr);

        DesiredNode node = graph.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found in graph: " + nodeIdStr);
        }

        return switch (action) {
            case PROVISION -> executeProvision(node, nodeId, tenancyId, graph);
            case DEPROVISION -> executeDeprovision(node, nodeId, tenancyId, graph);
        };
    }

    private Map<String, Object> executeProvision(DesiredNode node, NodeId nodeId, String tenancyId, DesiredStateGraph graph) {
        ProvisionContext context = new ProvisionContext(tenancyId, graph);
        ProvisionResult result = provisioner.provision(node, context);

        return switch (result) {
            case ProvisionResult.Success ignored -> Map.of(
                "nodeId", nodeId.value(),
                "action", "PROVISION",
                "status", "SUCCESS"
            );
            case ProvisionResult.Failed f -> Map.of(
                "nodeId", nodeId.value(),
                "action", "PROVISION",
                "status", "FAILED",
                "reason", f.reason()
            );
            case ProvisionResult.PendingApproval pa -> Map.of(
                "nodeId", nodeId.value(),
                "action", "PROVISION",
                "status", "PENDING_APPROVAL",
                "planReference", pa.planReference()
            );
        };
    }

    private Map<String, Object> executeDeprovision(DesiredNode node, NodeId nodeId, String tenancyId, DesiredStateGraph graph) {
        DeprovisionContext context = new DeprovisionContext(tenancyId, graph);
        DeprovisionResult result = provisioner.deprovision(node, context);

        return switch (result) {
            case DeprovisionResult.Success ignored -> Map.of(
                "nodeId", nodeId.value(),
                "action", "DEPROVISION",
                "status", "SUCCESS"
            );
            case DeprovisionResult.Failed f -> Map.of(
                "nodeId", nodeId.value(),
                "action", "DEPROVISION",
                "status", "FAILED",
                "reason", f.reason()
            );
            case DeprovisionResult.PendingApproval pa -> Map.of(
                "nodeId", nodeId.value(),
                "action", "DEPROVISION",
                "status", "PENDING_APPROVAL",
                "planReference", pa.planReference()
            );
        };
    }

    private static String requireString(Map<String, Object> input, String key) {
        Object value = input.get(key);
        Objects.requireNonNull(value, "Missing required input key: " + key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Input key '" + key + "' must be a String, got: " + value.getClass().getName());
        }
        return s;
    }
}
