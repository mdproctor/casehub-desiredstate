package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.*;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class DesiredStateGroupLifecycleService implements GroupLifecycleService {

    private final OrgRegistry registry;
    private final GroupInstanceStore instanceStore;

    @Inject
    public DesiredStateGroupLifecycleService(OrgRegistry registry, GroupInstanceStore instanceStore) {
        this.registry = registry;
        this.instanceStore = instanceStore;
    }

    @Override
    public GroupInstance createGroup(String specId, String tenancyId, FormationOrigin origin) {
        registry.findUnit(specId, tenancyId)
            .orElseThrow(() -> new OrgValidationException("specId",
                "no OrganizationalUnit found for specId '" + specId + "' in tenancy '" + tenancyId + "'"));

        var now = Instant.now();
        var instance = new GroupInstance(
            specId, specId, tenancyId, GroupLifecycleState.PROPOSED, origin,
            null, null, null,
            List.of(), List.of(), List.of(), now, now, 0);
        instanceStore.save(instance);
        return instance;
    }

    @Override
    public GroupInstance proposeGroup(String specId, String tenancyId, String proposerAgentId) {
        return createGroup(specId, tenancyId, FormationOrigin.PROPOSAL);
    }

    @Override
    public void approveProposal(String instanceId, String tenancyId) {
        transitionState(instanceId, tenancyId, GroupLifecycleState.PROPOSED, GroupLifecycleState.FORMING);
    }

    @Override
    public void rejectProposal(String instanceId, String tenancyId, String reason) {
        transitionState(instanceId, tenancyId, GroupLifecycleState.PROPOSED, GroupLifecycleState.REJECTED);
    }

    @Override
    public void activateGroup(String instanceId, String tenancyId) {
        transitionState(instanceId, tenancyId, GroupLifecycleState.FORMING, GroupLifecycleState.ACTIVE);
    }

    @Override
    public void dissolveGroup(String instanceId, String tenancyId, String reason) {
        var gi = requireInstance(instanceId, tenancyId);
        var dissolved = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(),
            GroupLifecycleState.DISSOLVING, gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), gi.roleAdoptions(), gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(dissolved);
    }

    @Override
    public void changeTopology(String instanceId, String newTopology,
                               String newTopologyVocabulary, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            newTopology, newTopologyVocabulary, gi.effectiveAuthority(),
            gi.members(), gi.roleAdoptions(), gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void joinGroup(String instanceId, String agentId, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var members = new ArrayList<>(gi.members());
        members.add(new ActualMembership(agentId, Instant.now()));
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            members, gi.roleAdoptions(), gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void leaveGroup(String instanceId, String agentId, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var members = gi.members().stream()
            .filter(m -> !m.agentId().equals(agentId))
            .collect(Collectors.toList());
        var roleAdoptions = gi.roleAdoptions().stream()
            .filter(ra -> !ra.agentId().equals(agentId))
            .collect(Collectors.toList());
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            members, roleAdoptions, gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void adoptRole(String instanceId, String agentId, String roleName, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var adoptions = new ArrayList<>(gi.roleAdoptions());
        adoptions.add(new RoleAdoption(agentId, roleName, null, Instant.now()));
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), adoptions, gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void releaseRole(String instanceId, String agentId, String roleName, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var adoptions = gi.roleAdoptions().stream()
            .filter(ra -> !(ra.agentId().equals(agentId) && ra.roleName().equals(roleName)))
            .collect(Collectors.toList());
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), adoptions, gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void commitToMission(String instanceId, String agentId, String missionName, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var commitments = new ArrayList<>(gi.missionCommitments());
        commitments.add(new MissionCommitment(agentId, missionName, Instant.now()));
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), gi.roleAdoptions(), commitments,
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void withdrawFromMission(String instanceId, String agentId, String missionName, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var commitments = gi.missionCommitments().stream()
            .filter(mc -> !(mc.agentId().equals(agentId) && mc.missionName().equals(missionName)))
            .collect(Collectors.toList());
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), gi.roleAdoptions(), commitments,
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public void fulfillMission(String instanceId, String missionName, String tenancyId) {
        var gi = requireInstance(instanceId, tenancyId);
        var commitments = gi.missionCommitments().stream()
            .filter(mc -> !mc.missionName().equals(missionName))
            .collect(Collectors.toList());
        var updated = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), gi.state(), gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), gi.roleAdoptions(), commitments,
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(updated);
    }

    @Override
    public Optional<GroupInstance> findInstance(String instanceId, String tenancyId) {
        return instanceStore.findById(instanceId, tenancyId);
    }

    @Override
    public List<GroupInstance> findInstances(GroupInstanceQuery query) {
        return instanceStore.find(query);
    }

    @Override
    public List<GroupInstance> instancesForAgent(String agentId, String tenancyId) {
        return instanceStore.find(new GroupInstanceQuery(tenancyId, null, null, agentId));
    }

    private GroupInstance requireInstance(String instanceId, String tenancyId) {
        return instanceStore.findById(instanceId, tenancyId)
            .orElseThrow(() -> new OrgValidationException("instanceId",
                "no GroupInstance found for '" + instanceId + "' in tenancy '" + tenancyId + "'"));
    }

    private void transitionState(String instanceId, String tenancyId,
                                  GroupLifecycleState expectedFrom, GroupLifecycleState to) {
        var gi = requireInstance(instanceId, tenancyId);
        if (gi.state() != expectedFrom) {
            throw new OrgValidationException("state",
                "expected " + expectedFrom + " but found " + gi.state());
        }
        var transitioned = new GroupInstance(
            gi.instanceId(), gi.specId(), gi.tenancyId(), to, gi.formationOrigin(),
            gi.topology(), gi.topologyVocabulary(), gi.effectiveAuthority(),
            gi.members(), gi.roleAdoptions(), gi.missionCommitments(),
            gi.createdAt(), Instant.now(), gi.version() + 1);
        instanceStore.save(transitioned);
    }
}
