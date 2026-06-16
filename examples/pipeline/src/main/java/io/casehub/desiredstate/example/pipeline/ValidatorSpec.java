package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;

public record ValidatorSpec(String schemaRef, double qualityThreshold, boolean anomalyDetection) implements NodeSpec {}
