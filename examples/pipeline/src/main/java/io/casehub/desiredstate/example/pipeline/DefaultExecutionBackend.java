package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.Set;

public class DefaultExecutionBackend implements ExecutionBackend {

    private static final Set<NodeType> PROCESSING_TYPES = Set.of(
            PipelineNodeTypes.INGESTION, PipelineNodeTypes.CLEANSER,
            PipelineNodeTypes.ENRICHER, PipelineNodeTypes.VALIDATOR,
            PipelineNodeTypes.TRANSFORMER, PipelineNodeTypes.SINK
    );

    private final PipelineWorld world;

    public DefaultExecutionBackend(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public boolean handles(DesiredNode node) {
        return PROCESSING_TYPES.contains(node.type());
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

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

        return new ProvisionResult.Failed("Unhandled processing type: " + type.value());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
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
