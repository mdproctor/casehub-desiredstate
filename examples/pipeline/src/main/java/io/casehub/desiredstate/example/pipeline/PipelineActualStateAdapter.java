package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads the actual state of the pipeline from {@link PipelineWorld}.
 * Translates world-level state (stage states, registry presence, review outcomes)
 * into the generic {@link ActualState} snapshot the reconciliation loop uses.
 */
public class PipelineActualStateAdapter implements ActualStateAdapter {

    private final PipelineWorld world;

    public PipelineActualStateAdapter(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
            PipelineNodeTypes.DATA_SOURCE, PipelineNodeTypes.SCHEMA,
            PipelineNodeTypes.INGESTION, PipelineNodeTypes.CLEANSER,
            PipelineNodeTypes.ENRICHER, PipelineNodeTypes.VALIDATOR,
            PipelineNodeTypes.TRANSFORMER, PipelineNodeTypes.SINK,
            PipelineNodeTypes.AI_REVIEW, PipelineNodeTypes.HUMAN_REVIEW
        );
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();

        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode node = entry.getValue();
            NodeType type = node.type();

            if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
                statuses.put(id, world.hasSource(id) ? NodeStatus.PRESENT : NodeStatus.ABSENT);

            } else if (type.equals(PipelineNodeTypes.SCHEMA)) {
                statuses.put(id, world.hasSchema(id.value()) ? NodeStatus.PRESENT : NodeStatus.ABSENT);

            } else if (type.equals(PipelineNodeTypes.AI_REVIEW) || type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
                PipelineWorld.ReviewEntry review = world.review(id);
                if (review != null && review.state() == PipelineWorld.ReviewState.RESOLVED) {
                    statuses.put(id, NodeStatus.PRESENT);
                } else {
                    statuses.put(id, NodeStatus.ABSENT);
                }

            } else {
                // Processing stages: translate StageState
                PipelineWorld.StageState state = world.stageState(id);
                if (state == null) {
                    statuses.put(id, NodeStatus.ABSENT);
                } else {
                    statuses.put(id, switch (state) {
                        case RUNNING, COMPLETED -> NodeStatus.PRESENT;
                        case IDLE, FAILED -> NodeStatus.ABSENT;
                        case DEGRADED, QUARANTINED -> NodeStatus.DRIFTED;
                    });
                }
            }
        }

        // Scan world for orphan nodes not in the desired graph
        for (NodeId stageId : world.allStages().keySet()) {
            if (!desired.nodes().containsKey(stageId)) {
                statuses.put(stageId, NodeStatus.PRESENT);
            }
        }
        for (NodeId sourceId : world.allSources().keySet()) {
            if (!desired.nodes().containsKey(sourceId)) {
                statuses.put(sourceId, NodeStatus.PRESENT);
            }
        }

        return new ActualState(statuses);
    }
}
