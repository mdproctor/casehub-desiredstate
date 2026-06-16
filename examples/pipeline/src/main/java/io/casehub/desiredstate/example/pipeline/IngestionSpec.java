package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;

public record IngestionSpec(String sourceRef, int batchSize, String format) implements NodeSpec {}
