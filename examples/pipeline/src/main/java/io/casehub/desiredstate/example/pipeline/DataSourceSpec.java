package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;

public record DataSourceSpec(String name, String format, String uri) implements NodeSpec {}
