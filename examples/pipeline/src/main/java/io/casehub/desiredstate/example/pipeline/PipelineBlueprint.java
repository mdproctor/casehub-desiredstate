package io.casehub.desiredstate.example.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Goal declaration for a data pipeline: defines stages from source through sink.
 * Use the Builder to construct a blueprint — dependencies are inferred from the
 * canonical topology, not declared explicitly.
 */
public class PipelineBlueprint {

    public record SourceEntry(String id, String format, String uri) {}

    public record SchemaEntry(String id, List<String> fields, int version) {
        public SchemaEntry {
            fields = List.copyOf(fields);
        }
    }

    public record IngestionEntry(String id, String sourceRef, int batchSize, String format) {}

    public record CleanserEntry(String id, List<String> rules, boolean deduplication, String nullHandling) {
        public CleanserEntry {
            rules = List.copyOf(rules);
        }
    }

    public record EnricherEntry(String id, String lookupSource, List<String> joinKeys, List<String> enrichFields) {
        public EnricherEntry {
            joinKeys = List.copyOf(joinKeys);
            enrichFields = List.copyOf(enrichFields);
        }
    }

    public record ValidatorEntry(String id, String schemaRef, double qualityThreshold, boolean anomalyDetection) {}

    public record TransformerEntry(String id, List<String> aggregations, List<String> reshapeRules,
                                   String outputFormat, boolean approvalRequired) {
        public TransformerEntry {
            aggregations = List.copyOf(aggregations);
            reshapeRules = List.copyOf(reshapeRules);
        }
    }

    public record SinkEntry(String id, String destination, String format, List<String> partitionKeys,
                            boolean approvalRequired) {
        public SinkEntry {
            partitionKeys = List.copyOf(partitionKeys);
        }
    }

    private final List<SourceEntry> sources;
    private final List<SchemaEntry> schemas;
    private final List<IngestionEntry> ingestions;
    private final List<CleanserEntry> cleansers;
    private final List<EnricherEntry> enrichers;
    private final List<ValidatorEntry> validators;
    private final List<TransformerEntry> transformers;
    private final List<SinkEntry> sinks;

    private PipelineBlueprint(List<SourceEntry> sources, List<SchemaEntry> schemas,
                              List<IngestionEntry> ingestions, List<CleanserEntry> cleansers,
                              List<EnricherEntry> enrichers, List<ValidatorEntry> validators,
                              List<TransformerEntry> transformers, List<SinkEntry> sinks) {
        this.sources = List.copyOf(sources);
        this.schemas = List.copyOf(schemas);
        this.ingestions = List.copyOf(ingestions);
        this.cleansers = List.copyOf(cleansers);
        this.enrichers = List.copyOf(enrichers);
        this.validators = List.copyOf(validators);
        this.transformers = List.copyOf(transformers);
        this.sinks = List.copyOf(sinks);
    }

    public List<SourceEntry> sources() { return sources; }
    public List<SchemaEntry> schemas() { return schemas; }
    public List<IngestionEntry> ingestions() { return ingestions; }
    public List<CleanserEntry> cleansers() { return cleansers; }
    public List<EnricherEntry> enrichers() { return enrichers; }
    public List<ValidatorEntry> validators() { return validators; }
    public List<TransformerEntry> transformers() { return transformers; }
    public List<SinkEntry> sinks() { return sinks; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<SourceEntry> sources = new ArrayList<>();
        private final List<SchemaEntry> schemas = new ArrayList<>();
        private final List<IngestionEntry> ingestions = new ArrayList<>();
        private final List<CleanserEntry> cleansers = new ArrayList<>();
        private final List<EnricherEntry> enrichers = new ArrayList<>();
        private final List<ValidatorEntry> validators = new ArrayList<>();
        private final List<TransformerEntry> transformers = new ArrayList<>();
        private final List<SinkEntry> sinks = new ArrayList<>();

        public Builder source(String id, String format, String uri) {
            sources.add(new SourceEntry(id, format, uri));
            return this;
        }

        public Builder schema(String id, List<String> fields, int version) {
            schemas.add(new SchemaEntry(id, fields, version));
            return this;
        }

        public Builder ingestion(String id, String sourceRef, int batchSize, String format) {
            ingestions.add(new IngestionEntry(id, sourceRef, batchSize, format));
            return this;
        }

        public Builder cleanser(String id, List<String> rules, boolean deduplication, String nullHandling) {
            cleansers.add(new CleanserEntry(id, rules, deduplication, nullHandling));
            return this;
        }

        public Builder enricher(String id, String lookupSource, List<String> joinKeys, List<String> enrichFields) {
            enrichers.add(new EnricherEntry(id, lookupSource, joinKeys, enrichFields));
            return this;
        }

        public Builder validator(String id, String schemaRef, double qualityThreshold, boolean anomalyDetection) {
            validators.add(new ValidatorEntry(id, schemaRef, qualityThreshold, anomalyDetection));
            return this;
        }

        public Builder transformer(String id, List<String> aggregations, List<String> reshapeRules, String outputFormat) {
            return transformer(id, aggregations, reshapeRules, outputFormat, false);
        }

        public Builder transformer(String id, List<String> aggregations, List<String> reshapeRules,
                                    String outputFormat, boolean approvalRequired) {
            transformers.add(new TransformerEntry(id, aggregations, reshapeRules, outputFormat, approvalRequired));
            return this;
        }

        public Builder sink(String id, String destination, String format, List<String> partitionKeys) {
            return sink(id, destination, format, partitionKeys, false);
        }

        public Builder sink(String id, String destination, String format, List<String> partitionKeys,
                            boolean approvalRequired) {
            sinks.add(new SinkEntry(id, destination, format, partitionKeys, approvalRequired));
            return this;
        }

        public PipelineBlueprint build() {
            return new PipelineBlueprint(sources, schemas, ingestions, cleansers,
                enrichers, validators, transformers, sinks);
        }
    }
}
