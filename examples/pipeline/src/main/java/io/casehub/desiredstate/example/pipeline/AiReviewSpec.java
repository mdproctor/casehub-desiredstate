package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record AiReviewSpec(NodeId targetNodeId, String errorDetail) implements NodeSpec {}
