package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

public record SinkSpec(String destination, String format, List<String> partitionKeys) implements NodeSpec {
    public SinkSpec {
        partitionKeys = List.copyOf(partitionKeys);
    }
}
