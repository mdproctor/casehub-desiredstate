package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmergencePromotionListenerTest {

    private final DesiredStateOrgAdapterTest.InMemoryOrgRegistry registry =
        new DesiredStateOrgAdapterTest.InMemoryOrgRegistry();
    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final DesiredStateGroupLifecycleService lifecycleService =
        new DesiredStateGroupLifecycleService(registry, instanceStore);
    private final EmergencePromotionListener listener =
        new EmergencePromotionListener(lifecycleService, registry);

    @Test void promotesSwarmTeamToGroup() {
        var data = new SwarmTeamFormedData(
            "detected-team-1", "t1",
            List.of("agent-a", "agent-b"), "COACTIVE");

        listener.promote(data);

        assertThat(registry.findUnit("detected-team-1", "t1")).isPresent();
        assertThat(instanceStore.findById("detected-team-1", "t1")).isPresent();
        assertThat(instanceStore.findById("detected-team-1", "t1").get().state())
            .isEqualTo(GroupLifecycleState.PROPOSED);
    }

    @Test void coactiveRelationDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-swarm", "t1", List.of("a1"), "COACTIVE");

        listener.promote(data);

        var unit = registry.findUnit("team-swarm", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void complementaryRelationDefaultsToHiveTopology() {
        var data = new SwarmTeamFormedData(
            "team-hive", "t1", List.of("a1"), "COMPLEMENTARY");

        listener.promote(data);

        var unit = registry.findUnit("team-hive", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("hive");
    }

    @Test void sharedInterestDefaultsToMeshTopology() {
        var data = new SwarmTeamFormedData(
            "team-mesh", "t1", List.of("a1"), "SHARED_INTEREST");

        listener.promote(data);

        var unit = registry.findUnit("team-mesh", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("mesh");
    }

    @Test void sharedSignalDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-signal", "t1", List.of("a1"), "SHARED_SIGNAL");

        listener.promote(data);

        var unit = registry.findUnit("team-signal", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void unknownRelationDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-unknown", "t1", List.of("a1"), "SOMETHING_ELSE");

        listener.promote(data);

        var unit = registry.findUnit("team-unknown", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void nullRelationDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-null", "t1", List.of("a1"), null);

        listener.promote(data);

        var unit = registry.findUnit("team-null", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void membersAreSeededInUnit() {
        var data = new SwarmTeamFormedData(
            "team-members", "t1",
            List.of("agent-x", "agent-y", "agent-z"), "COACTIVE");

        listener.promote(data);

        var unit = registry.findUnit("team-members", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().members()).hasSize(3);
        assertThat(unit.get().members().stream().map(Membership::agentId))
            .containsExactlyInAnyOrder("agent-x", "agent-y", "agent-z");
    }

    @Test void formationOriginIsEmergence() {
        var data = new SwarmTeamFormedData(
            "team-emerge", "t1", List.of("a1"), "COACTIVE");

        listener.promote(data);

        var gi = instanceStore.findById("team-emerge", "t1");
        assertThat(gi).isPresent();
        assertThat(gi.get().formationOrigin()).isEqualTo(FormationOrigin.EMERGENCE);
    }

    @Test void selfDeterminationDefaultsToMidRange() {
        var data = new SwarmTeamFormedData(
            "team-sd", "t1", List.of("a1"), "COACTIVE");

        listener.promote(data);

        var unit = registry.findUnit("team-sd", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().selfDetermination()).isEqualTo(0.5);
    }
}
