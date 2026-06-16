package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory simulation of the pipeline world. Holds mutable state for all
 * pipeline entities — data sources, schemas, lookup sources, processing stages,
 * reviews — and provides operations that the provisioner, adapter, and fault
 * policy implementations use.
 */
@ApplicationScoped
public class PipelineWorld {

    // --- Enums ---

    public enum StageState {
        IDLE, RUNNING, COMPLETED, FAILED, QUARANTINED, DEGRADED
    }

    public enum ReviewState {
        PENDING, RESOLVED, UNRESOLVED
    }

    // --- Records ---

    public record StageEntry(NodeType nodeType, StageState state, String inputSchema, String outputSchema,
                             long processed, long failed, long quarantined, String errorDetail) {

        public StageEntry withState(StageState newState) {
            return new StageEntry(nodeType, newState, inputSchema, outputSchema, processed, failed, quarantined, null);
        }

        public StageEntry withError(StageState newState, String error) {
            return new StageEntry(nodeType, newState, inputSchema, outputSchema, processed, failed, quarantined, error);
        }
    }

    public record SchemaDefinition(String name, List<String> fields, int version) {
        public SchemaDefinition {
            fields = List.copyOf(fields);
        }
    }

    public record LookupSourceEntry(String name) {}

    public record ReviewEntry(NodeId targetNode, ReviewState state) {}

    public record DataSourceEntry(String name, String format, String uri) {}

    // --- Registries ---

    private final ConcurrentHashMap<NodeId, StageEntry> stages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SchemaDefinition> schemas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LookupSourceEntry> lookupSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, ReviewEntry> reviews = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, DataSourceEntry> dataSources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, List<NodeId>> downstreamMap = new ConcurrentHashMap<>();

    // --- Data source operations ---

    public void registerSource(NodeId id, DataSourceEntry entry) {
        dataSources.put(id, entry);
    }

    public boolean hasSource(NodeId id) {
        return dataSources.containsKey(id);
    }

    public void removeSource(NodeId id) {
        dataSources.remove(id);
    }

    public Map<NodeId, DataSourceEntry> allSources() {
        return Collections.unmodifiableMap(dataSources);
    }

    // --- Schema operations ---

    public void registerSchema(String name, SchemaDefinition schema) {
        schemas.put(name, schema);
    }

    public SchemaDefinition schema(String name) {
        return schemas.get(name);
    }

    public boolean hasSchema(String name) {
        return schemas.containsKey(name);
    }

    public void removeSchema(String name) {
        schemas.remove(name);
    }

    public Map<String, SchemaDefinition> allSchemas() {
        return Collections.unmodifiableMap(schemas);
    }

    // --- Lookup source operations ---

    public void registerLookupSource(String name, LookupSourceEntry entry) {
        lookupSources.put(name, entry);
    }

    public boolean hasLookupSource(String name) {
        return lookupSources.containsKey(name);
    }

    // --- Stage operations ---

    public void setStage(NodeId id, StageEntry entry) {
        stages.put(id, entry);
    }

    public StageState stageState(NodeId id) {
        StageEntry entry = stages.get(id);
        return entry == null ? null : entry.state();
    }

    public StageEntry stageEntry(NodeId id) {
        return stages.get(id);
    }

    public void failStage(NodeId id, String errorDetail) {
        StageEntry entry = stages.get(id);
        if (entry != null) {
            stages.put(id, entry.withError(StageState.FAILED, errorDetail));
        }
    }

    public void quarantineStage(NodeId id) {
        StageEntry entry = stages.get(id);
        if (entry != null) {
            stages.put(id, entry.withState(StageState.QUARANTINED));
        }
    }

    public void degradeStage(NodeId id) {
        StageEntry entry = stages.get(id);
        if (entry != null) {
            stages.put(id, entry.withState(StageState.DEGRADED));
        }
    }

    public Map<NodeId, StageEntry> allStages() {
        return Collections.unmodifiableMap(stages);
    }

    // --- Downstream cascade ---

    public void registerDownstream(NodeId upstream, NodeId downstream) {
        downstreamMap.computeIfAbsent(upstream, k -> new CopyOnWriteArrayList<>()).add(downstream);
    }

    /**
     * Removes a stage and cascades downstream stages to IDLE.
     */
    public void removeStage(NodeId id) {
        stages.remove(id);

        // Cascade: set all downstream stages to IDLE
        List<NodeId> downstream = downstreamMap.get(id);
        if (downstream != null) {
            for (NodeId downId : downstream) {
                StageEntry entry = stages.get(downId);
                if (entry != null) {
                    stages.put(downId, entry.withState(StageState.IDLE));
                }
                // Recursive cascade
                cascadeIdle(downId);
            }
        }
        downstreamMap.remove(id);
    }

    private void cascadeIdle(NodeId id) {
        List<NodeId> downstream = downstreamMap.get(id);
        if (downstream != null) {
            for (NodeId downId : downstream) {
                StageEntry entry = stages.get(downId);
                if (entry != null) {
                    stages.put(downId, entry.withState(StageState.IDLE));
                }
                cascadeIdle(downId);
            }
        }
    }

    // --- Review operations ---

    public void addReview(NodeId reviewNodeId, NodeId targetNode) {
        reviews.put(reviewNodeId, new ReviewEntry(targetNode, ReviewState.PENDING));
    }

    public ReviewEntry review(NodeId reviewNodeId) {
        return reviews.get(reviewNodeId);
    }

    public void removeReview(NodeId reviewNodeId) {
        reviews.remove(reviewNodeId);
    }

    public Map<NodeId, ReviewEntry> allReviews() {
        return Collections.unmodifiableMap(reviews);
    }

    public boolean hasReviewForTarget(NodeId targetNodeId) {
        return reviews.values().stream()
            .anyMatch(r -> r.targetNode().equals(targetNodeId));
    }

    public ReviewEntry reviewForTarget(NodeId targetNodeId) {
        return reviews.values().stream()
            .filter(r -> r.targetNode().equals(targetNodeId))
            .findFirst()
            .orElse(null);
    }

    // --- Resolution operations ---

    /**
     * Sets the outcome of an AI review for the given target node.
     */
    public void setAiReviewOutcome(NodeId targetNodeId, boolean resolved) {
        for (Map.Entry<NodeId, ReviewEntry> entry : reviews.entrySet()) {
            if (entry.getValue().targetNode().equals(targetNodeId)) {
                reviews.put(entry.getKey(),
                    new ReviewEntry(targetNodeId, resolved ? ReviewState.RESOLVED : ReviewState.UNRESOLVED));
                return;
            }
        }
    }

    /**
     * Resolves a human review for the given target node.
     */
    public void resolveHumanReview(NodeId targetNodeId) {
        for (Map.Entry<NodeId, ReviewEntry> entry : reviews.entrySet()) {
            if (entry.getValue().targetNode().equals(targetNodeId)) {
                reviews.put(entry.getKey(), new ReviewEntry(targetNodeId, ReviewState.RESOLVED));
                return;
            }
        }
    }

    /**
     * Clears a FAILED stage error, setting it back to IDLE (ready for re-provisioning).
     */
    public void clearStageError(NodeId nodeId) {
        StageEntry entry = stages.get(nodeId);
        if (entry != null && entry.state() == StageState.FAILED) {
            stages.put(nodeId, entry.withState(StageState.IDLE));
        }
    }

    /**
     * Approves a schema change: updates version in registry, then walks all stages
     * whose inputSchema or outputSchema matches the schema name and sets DEGRADED
     * stages back to RUNNING.
     */
    public void approveSchemaChange(String schemaName, int newVersion) {
        SchemaDefinition existing = schemas.get(schemaName);
        if (existing != null) {
            schemas.put(schemaName, new SchemaDefinition(existing.name(), existing.fields(), newVersion));
        }

        // Walk stages and restore DEGRADED ones that reference this schema
        for (Map.Entry<NodeId, StageEntry> entry : stages.entrySet()) {
            StageEntry stage = entry.getValue();
            if (stage.state() == StageState.DEGRADED) {
                boolean matchesInput = schemaName.equals(stage.inputSchema());
                boolean matchesOutput = schemaName.equals(stage.outputSchema());
                if (matchesInput || matchesOutput) {
                    stages.put(entry.getKey(), stage.withState(StageState.RUNNING));
                }
            }
        }
    }
}
