package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.Set;

/**
 * Provisions and deprovisions pipeline nodes by mutating the {@link PipelineWorld}.
 * Dispatches on {@link NodeType} to handle each kind of pipeline entity.
 */
public class PipelineProvisioner implements NodeProvisioner {

    private final PipelineWorld world;

    public PipelineProvisioner(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            DataSourceSpec spec = (DataSourceSpec) node.spec();
            world.registerSource(node.id(),
                new PipelineWorld.DataSourceEntry(spec.name(), spec.format(), spec.uri()));
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.registerSchema(spec.name(),
                new PipelineWorld.SchemaDefinition(spec.name(), spec.fields(), spec.version()));
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.INGESTION)) {
            IngestionSpec spec = (IngestionSpec) node.spec();
            if (!world.hasSource(NodeId.of(spec.sourceRef()))) {
                return new ProvisionResult.Failed(
                    "Data source not found: " + spec.sourceRef());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.CLEANSER)) {
            Set<NodeId> deps = context.graph().dependenciesOf(node.id());
            boolean hasUpstream = deps.stream().anyMatch(dep -> {
                DesiredNode depNode = context.graph().nodes().get(dep);
                return depNode != null && PipelineNodeTypes.INGESTION.equals(depNode.type());
            });
            if (!hasUpstream) {
                return new ProvisionResult.Failed("No upstream ingestion stage found");
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.ENRICHER)) {
            EnricherSpec spec = (EnricherSpec) node.spec();
            if (!world.hasLookupSource(spec.lookupSource())) {
                return new ProvisionResult.Failed(
                    "Lookup source not found: " + spec.lookupSource());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.VALIDATOR)) {
            ValidatorSpec spec = (ValidatorSpec) node.spec();
            if (!world.hasSchema(spec.schemaRef())) {
                return new ProvisionResult.Failed(
                    "Schema not found: " + spec.schemaRef());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.TRANSFORMER)) {
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SINK)) {
            world.setStage(node.id(), runningStage(type));
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.AI_REVIEW)) {
            AiReviewSpec spec = (AiReviewSpec) node.spec();
            NodeId target = spec.targetNodeId();

            // Check if a pre-set outcome exists for this review
            PipelineWorld.ReviewEntry existing = world.review(node.id());
            if (existing != null) {
                if (existing.state() == PipelineWorld.ReviewState.RESOLVED) {
                    world.clearStageError(target);
                }
                // Both RESOLVED and UNRESOLVED: review already handled
                return new ProvisionResult.Success();
            }

            // No pre-set outcome — register as PENDING
            world.addReview(node.id(), target);
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            HumanReviewSpec spec = (HumanReviewSpec) node.spec();
            world.addReview(node.id(), spec.targetNodeId());
            return new ProvisionResult.Success();
        }

        return new ProvisionResult.Failed("Unknown node type: " + type.value());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            world.removeSource(node.id());
            return new DeprovisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.removeSchema(spec.name());
            return new DeprovisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.AI_REVIEW) || type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            world.removeReview(node.id());
            return new DeprovisionResult.Success();
        }

        // All processing stages: remove with downstream cascade
        world.removeStage(node.id());
        return new DeprovisionResult.Success();
    }

    private void registerDownstream(NodeId nodeId, DesiredStateGraph graph) {
        Set<NodeId> dependents = graph.dependentsOf(nodeId);
        for (NodeId dependent : dependents) {
            world.registerDownstream(nodeId, dependent);
        }
    }

    private PipelineWorld.StageEntry runningStage(NodeType nodeType) {
        return new PipelineWorld.StageEntry(
            nodeType, PipelineWorld.StageState.RUNNING, null, null, 0, 0, 0, null);
    }
}
