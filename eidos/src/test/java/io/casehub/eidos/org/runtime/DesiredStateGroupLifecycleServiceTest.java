package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesiredStateGroupLifecycleServiceTest {

    private final DesiredStateOrgAdapterTest.InMemoryOrgRegistry registry =
        new DesiredStateOrgAdapterTest.InMemoryOrgRegistry();
    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final DesiredStateGroupLifecycleService service =
        new DesiredStateGroupLifecycleService(registry, instanceStore);

    @Test void createGroupRegistersAndReturnsProposedInstance() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1")
            .selfDetermination(0.5).build();
        registry.registerUnit(unit);

        var instance = service.createGroup("team-1", "t1", FormationOrigin.TOP_DOWN);

        assertThat(instance).isNotNull();
        assertThat(instance.specId()).isEqualTo("team-1");
        assertThat(instance.state()).isEqualTo(GroupLifecycleState.PROPOSED);
        assertThat(instance.formationOrigin()).isEqualTo(FormationOrigin.TOP_DOWN);
    }

    @Test void createGroupForMissingSpecThrows() {
        assertThatThrownBy(() -> service.createGroup("missing", "t1", FormationOrigin.TOP_DOWN))
            .isInstanceOf(OrgValidationException.class);
    }

    @Test void proposeGroupSetsProposalOrigin() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-p").name("Team P").tenancyId("t1").build();
        registry.registerUnit(unit);

        var instance = service.proposeGroup("team-p", "t1", "agent-1");

        assertThat(instance.formationOrigin()).isEqualTo(FormationOrigin.PROPOSAL);
    }

    @Test void approveProposalTransitionsToForming() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-a").name("Team A").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-a", "t1", FormationOrigin.PROPOSAL);

        service.approveProposal("team-a", "t1");

        assertThat(instanceStore.findById("team-a", "t1").get().state())
            .isEqualTo(GroupLifecycleState.FORMING);
    }

    @Test void rejectProposalTransitionsToRejected() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-r").name("Team R").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-r", "t1", FormationOrigin.PROPOSAL);

        service.rejectProposal("team-r", "t1", "not viable");

        assertThat(instanceStore.findById("team-r", "t1").get().state())
            .isEqualTo(GroupLifecycleState.REJECTED);
    }

    @Test void activateGroupTransitionsFormingToActive() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-act").name("Team Act").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-act", "t1", FormationOrigin.TOP_DOWN);
        service.approveProposal("team-act", "t1");

        service.activateGroup("team-act", "t1");

        assertThat(instanceStore.findById("team-act", "t1").get().state())
            .isEqualTo(GroupLifecycleState.ACTIVE);
    }

    @Test void dissolveGroupTransitionsToDissolvingState() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-2").name("Team 2").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-2", "t1", FormationOrigin.TOP_DOWN);

        service.dissolveGroup("team-2", "t1", "no longer needed");

        var found = instanceStore.findById("team-2", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().state()).isEqualTo(GroupLifecycleState.DISSOLVING);
    }

    @Test void findInstanceDelegatesToStore() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-3").name("Team 3").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-3", "t1", FormationOrigin.TOP_DOWN);

        var found = service.findInstance("team-3", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().specId()).isEqualTo("team-3");
    }

    @Test void joinGroupAddsMember() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-4").name("Team 4").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-4", "t1", FormationOrigin.TOP_DOWN);

        service.joinGroup("team-4", "agent-1", "t1");

        var found = instanceStore.findById("team-4", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().members()).anyMatch(m -> m.agentId().equals("agent-1"));
    }

    @Test void leaveGroupRemovesMember() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-5").name("Team 5").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-5", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-5", "agent-2", "t1");

        service.leaveGroup("team-5", "agent-2", "t1");

        var found = instanceStore.findById("team-5", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().members()).noneMatch(m -> m.agentId().equals("agent-2"));
    }

    @Test void changeTopologyUpdatesInstance() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-6").name("Team 6").tenancyId("t1")
            .topology("swarm").build();
        registry.registerUnit(unit);
        service.createGroup("team-6", "t1", FormationOrigin.TOP_DOWN);

        service.changeTopology("team-6", "mesh", null, "t1");

        var found = instanceStore.findById("team-6", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().topology()).isEqualTo("mesh");
    }

    @Test void adoptRoleAddsToRoleAdoptions() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-7").name("Team 7").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-7", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-7", "agent-3", "t1");

        service.adoptRole("team-7", "agent-3", "coordinator", "t1");

        var found = instanceStore.findById("team-7", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().roleAdoptions()).anyMatch(
            ra -> ra.agentId().equals("agent-3") && ra.roleName().equals("coordinator"));
    }

    @Test void releaseRoleRemovesFromRoleAdoptions() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-8").name("Team 8").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-8", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-8", "agent-4", "t1");
        service.adoptRole("team-8", "agent-4", "analyst", "t1");

        service.releaseRole("team-8", "agent-4", "analyst", "t1");

        var found = instanceStore.findById("team-8", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().roleAdoptions()).noneMatch(
            ra -> ra.agentId().equals("agent-4") && ra.roleName().equals("analyst"));
    }

    @Test void commitToMissionAddsMissionCommitment() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-9").name("Team 9").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-9", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-9", "agent-5", "t1");

        service.commitToMission("team-9", "agent-5", "investigate-fraud", "t1");

        var found = instanceStore.findById("team-9", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().missionCommitments()).anyMatch(
            mc -> mc.agentId().equals("agent-5") && mc.missionName().equals("investigate-fraud"));
    }

    @Test void fulfillMissionRemovesAllCommitments() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-10").name("Team 10").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-10", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-10", "agent-6", "t1");
        service.commitToMission("team-10", "agent-6", "deploy-fix", "t1");

        service.fulfillMission("team-10", "deploy-fix", "t1");

        var found = instanceStore.findById("team-10", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().missionCommitments()).noneMatch(
            mc -> mc.missionName().equals("deploy-fix"));
    }

    @Test void invalidStateTransitionThrows() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-bad").name("Bad").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-bad", "t1", FormationOrigin.TOP_DOWN);

        assertThatThrownBy(() -> service.activateGroup("team-bad", "t1"))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("expected FORMING");
    }

    @Test void dissolveGroupForMissingInstanceThrows() {
        assertThatThrownBy(() -> service.dissolveGroup("nonexistent", "t1", "reason"))
            .isInstanceOf(OrgValidationException.class);
    }
}
