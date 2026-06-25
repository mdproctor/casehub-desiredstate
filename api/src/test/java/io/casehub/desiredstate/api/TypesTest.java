package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypesTest {
    record TestSpec(String name) implements NodeSpec {}

    @Test void graphMutation_sealedExhaustive() {
        var node = new DesiredNode(new NodeId("a"), new NodeType("t"), new TestSpec("x"), false);
        GraphMutation mutation = new GraphMutation.AddNode(node);
        String result = switch (mutation) {
            case GraphMutation.AddNode m -> "add:" + m.node().id().value();
            case GraphMutation.RemoveNode m -> "remove:" + m.id().value();
            case GraphMutation.UpdateNode m -> "update:" + m.id().value();
            case GraphMutation.AddDependency m -> "addDep:" + m.dependency().from().value();
            case GraphMutation.RemoveDependency m -> "rmDep:" + m.dependency().from().value();
        };
        assertThat(result).isEqualTo("add:a");
    }

    @Test void provisionResult_sealed() {
        ProvisionResult success = new ProvisionResult.Success();
        ProvisionResult failed = new ProvisionResult.Failed("timeout");
        assertThat(success).isInstanceOf(ProvisionResult.Success.class);
        assertThat(((ProvisionResult.Failed) failed).reason()).isEqualTo("timeout");
    }

    @Test void provisionResult_pendingApproval() {
        var nodeId = new NodeId("db-prod");
        ProvisionResult pa = new ProvisionResult.PendingApproval(nodeId, "plan-ref-123");
        assertThat(pa).isInstanceOf(ProvisionResult.PendingApproval.class);
        assertThat(((ProvisionResult.PendingApproval) pa).nodeId()).isEqualTo(nodeId);
        assertThat(((ProvisionResult.PendingApproval) pa).planReference()).isEqualTo("plan-ref-123");
    }

    @Test void deprovisionResult_sealed() {
        DeprovisionResult failed = new DeprovisionResult.Failed("locked");
        assertThat(((DeprovisionResult.Failed) failed).reason()).isEqualTo("locked");
    }

    @Test void deprovisionResult_pendingApproval() {
        var nodeId = new NodeId("db-prod");
        DeprovisionResult pa = new DeprovisionResult.PendingApproval(nodeId, "destroy-plan-456");
        assertThat(pa).isInstanceOf(DeprovisionResult.PendingApproval.class);
        assertThat(((DeprovisionResult.PendingApproval) pa).nodeId()).isEqualTo(nodeId);
        assertThat(((DeprovisionResult.PendingApproval) pa).planReference()).isEqualTo("destroy-plan-456");
    }

    @Test void stepOutcome_sealed() {
        StepOutcome outcome = new StepOutcome.Failed("boom");
        String result = switch (outcome) {
            case StepOutcome.Succeeded s -> "ok";
            case StepOutcome.Failed f -> "fail:" + f.reason();
            case StepOutcome.Skipped s -> "skip:" + s.reason();
        };
        assertThat(result).isEqualTo("fail:boom");
    }

    @Test void transitionResult_outcomes() {
        var id = new NodeId("a");
        var result = new TransitionResult(Map.of(id, new StepOutcome.Succeeded()));
        assertThat(result.outcomes()).containsKey(id);
    }

    @Test void actualState_statuses() {
        var id = new NodeId("lib");
        var state = new ActualState(Map.of(id, NodeStatus.PRESENT));
        assertThat(state.statuses().get(id)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test void faultEvent_fields() {
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "hero raid");
        assertThat(event.type()).isEqualTo(FaultType.NODE_DESTROYED);
    }

    @Test void stateEvent_fields() {
        var event = new StateEvent(new NodeId("lib"), NodeStatus.ABSENT, "destroyed");
        assertThat(event.newStatus()).isEqualTo(NodeStatus.ABSENT);
    }

    @Test void reconciliationResult_fields() {
        var result = new ReconciliationResult(
            Set.of(new NodeId("a")), Set.of(new NodeId("b")), Set.of(), List.of());
        assertThat(result.resolved()).hasSize(1);
        assertThat(result.drifted()).hasSize(1);
    }
}
