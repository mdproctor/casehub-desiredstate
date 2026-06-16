package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record TransformerSpec(List<String> aggregations, List<String> reshapeRules, String outputFormat) implements NodeSpec {
    public TransformerSpec {
        aggregations = List.copyOf(aggregations);
        reshapeRules = List.copyOf(reshapeRules);
    }
}
