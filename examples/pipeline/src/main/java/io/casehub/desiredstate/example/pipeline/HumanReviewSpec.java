package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record HumanReviewSpec(NodeId targetNodeId, String errorDetail, String escalationReason) implements NodeSpec {}
