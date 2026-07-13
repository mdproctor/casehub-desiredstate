package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class CbrProposalTracker {

    private final ConcurrentHashMap<String, List<CbrProposal>> pending =
        new ConcurrentHashMap<>();

    public void recordProposal(String tenancyId, CbrProposal proposal) {
        pending.computeIfAbsent(tenancyId, k -> new CopyOnWriteArrayList<>())
            .add(proposal);
    }

    public List<CbrOutcomeData> matchOutcomes(String tenancyId,
            TransitionResult result, DesiredStateGraph currentGraph) {
        List<CbrProposal> proposals = pending.remove(tenancyId);
        if (proposals == null || proposals.isEmpty()) return List.of();

        Instant now = Instant.now();
        List<CbrOutcomeData> outcomes = new ArrayList<>();

        for (CbrProposal proposal : proposals) {
            Map<String, String> nodeOutcomes = new LinkedHashMap<>();
            int success = 0, failure = 0;

            for (NodeId nodeId : proposal.affectedNodeIds()) {
                StepOutcome outcome = result.outcomes().get(nodeId);
                if (outcome == null) {
                    if (!currentGraph.nodes().containsKey(nodeId)) {
                        nodeOutcomes.put(nodeId.value(), "SUPERSEDED");
                    } else {
                        nodeOutcomes.put(nodeId.value(), "ALREADY_PRESENT");
                        success++;
                    }
                } else {
                    switch (outcome) {
                        case StepOutcome.Succeeded s -> {
                            nodeOutcomes.put(nodeId.value(), "SUCCEEDED");
                            success++;
                        }
                        case StepOutcome.Failed f -> {
                            nodeOutcomes.put(nodeId.value(), "FAILED");
                            failure++;
                        }
                        case StepOutcome.Skipped s ->
                            nodeOutcomes.put(nodeId.value(), "SKIPPED");
                        case StepOutcome.Rejected r -> {
                            nodeOutcomes.put(nodeId.value(), "REJECTED");
                            failure++;
                        }
                    }
                }
            }

            int resolved = success + failure;
            if (resolved == 0) continue;
            double successRate = (double) success / resolved;
            outcomes.add(new CbrOutcomeData(
                tenancyId, proposal.sourceId(), proposal.path(),
                nodeOutcomes, success, failure, resolved, successRate,
                proposal.timestamp(), now));
        }
        return outcomes;
    }

    public void clearTenant(String tenancyId) {
        pending.remove(tenancyId);
    }
}
