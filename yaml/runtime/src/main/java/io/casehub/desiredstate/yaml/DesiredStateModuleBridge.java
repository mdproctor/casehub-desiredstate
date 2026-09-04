package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.model.YamlRule;
import io.casehub.yaml.core.module.ModuleBridge;
import io.casehub.yaml.core.module.SectionContentRewriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DesiredStateModuleBridge implements ModuleBridge<DesiredStateModuleContent> {

    private static final String NODES = "nodes";
    private static final String RULES = "rules";
    private static final String INVARIANTS = "invariants";

    private final ObjectMapper mapper;

    public DesiredStateModuleBridge(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DesiredStateModuleContent fromSections(Map<String, Map<String, Object>> sections) {
        return new DesiredStateModuleContent(
                convertSection(sections.getOrDefault(NODES, Map.of()), YamlNode.class),
                convertSection(sections.getOrDefault(RULES, Map.of()), YamlRule.class),
                convertSection(sections.getOrDefault(INVARIANTS, Map.of()), YamlInvariant.class));
    }

    @Override
    public Map<String, Map<String, Object>> toSections(DesiredStateModuleContent content) {
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        if (!content.nodes().isEmpty()) {
            sections.put(NODES, toRawSection(content.nodes()));
        }
        if (!content.rules().isEmpty()) {
            sections.put(RULES, toRawSection(content.rules()));
        }
        if (!content.invariants().isEmpty()) {
            sections.put(INVARIANTS, toRawSection(content.invariants()));
        }
        return sections;
    }

    @Override
    public SectionContentRewriter rewriter() {
        return (sectionName, entryKey, entryValue, alias, moduleKeys) -> {
            if (!NODES.equals(sectionName)) { return entryValue; }
            if (!(entryValue instanceof Map<?, ?> rawMap)) { return entryValue; }

            @SuppressWarnings("unchecked")
            Map<String, Object> nodeMap = (Map<String, Object>) rawMap;
            Object depsObj = nodeMap.get("dependsOn");
            if (!(depsObj instanceof List<?> deps)) { return entryValue; }

            List<Object> rewritten = new ArrayList<>(deps.size());
            for (Object dep : deps) {
                String depId = depNodeId(dep);
                boolean isOptional = isOptionalDep(dep);

                if (moduleKeys.contains(depId)) {
                    String aliased = alias + "." + depId;
                    rewritten.add(isOptional
                            ? Map.of("node", aliased, "optional", true)
                            : aliased);
                } else {
                    rewritten.add(dep);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>(nodeMap);
            result.put("dependsOn", rewritten);
            return result;
        };
    }

    private <V> Map<String, V> convertSection(Map<String, Object> raw, Class<V> type) {
        Map<String, V> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), mapper.convertValue(entry.getValue(), type));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <V> Map<String, Object> toRawSection(Map<String, V> typed) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, V> entry : typed.entrySet()) {
            raw.put(entry.getKey(), mapper.convertValue(entry.getValue(), Map.class));
        }
        return raw;
    }

    private static String depNodeId(Object dep) {
        if (dep instanceof String s) { return s; }
        if (dep instanceof Map<?, ?> m) { return (String) m.get("node"); }
        return dep.toString();
    }

    private static boolean isOptionalDep(Object dep) {
        if (dep instanceof Map<?, ?> m) {
            return Boolean.TRUE.equals(m.get("optional"));
        }
        return false;
    }
}
