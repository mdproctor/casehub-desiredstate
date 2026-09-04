package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.model.YamlRule;

import java.util.Map;

public record DesiredStateModuleContent(
        Map<String, YamlNode> nodes,
        Map<String, YamlRule> rules,
        Map<String, YamlInvariant> invariants) {

    public DesiredStateModuleContent {
        if (nodes == null) { nodes = Map.of(); }
        if (rules == null) { rules = Map.of(); }
        if (invariants == null) { invariants = Map.of(); }
    }

    public static DesiredStateModuleContent empty() {
        return new DesiredStateModuleContent(Map.of(), Map.of(), Map.of());
    }
}
