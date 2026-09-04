package io.casehub.desiredstate.annotations.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PatternEvaluator {

    private PatternEvaluator() {}

    public static <N> List<Map<String, N>> evaluate(
            io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
            List<PatternParameterDescriptor> patterns,
            String[] bindingNames) {

        List<List<N>> matchSets = new ArrayList<>();
        for (PatternParameterDescriptor p : patterns) {
            if (p.kind() == PatternKind.MATCH) {
                if ("*".equals(p.nodeType())) {
                    matchSets.add(new ArrayList<>(view.nodes().values()));
                } else {
                    matchSets.add(view.nodes().values().stream()
                                      .filter(n -> view.nodeType(n).equals(p.nodeType()))
                                      .toList());
                }
            }
        }

        List<Map<String, N>> results = new ArrayList<>();
        for (List<N> tuple : PatternMatchingSupport.crossProduct(matchSets)) {
            Map<String, N> bindings = new LinkedHashMap<>();
            int            matchIdx = 0;
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).kind() == PatternKind.MATCH) {
                    bindings.put(bindingNames[i], tuple.get(matchIdx++));
                }
            }
            expandChain(view, patterns, bindingNames, bindings, 0, results);
        }
        return results;
    }

    private static <N> void expandChain(
            io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
            List<PatternParameterDescriptor> patterns, String[] bindingNames,
            Map<String, N> bindings, int startIndex,
            List<Map<String, N>> results) {
        int idx = startIndex;
        while (idx < patterns.size() && patterns.get(idx).kind() == PatternKind.MATCH) {
            idx++;
        }
        if (idx >= patterns.size()) {
            results.add(new LinkedHashMap<>(bindings));
            return;
        }

        PatternParameterDescriptor p = patterns.get(idx);

        switch (p.kind()) {
            case DIRECT_DEP -> {
                N refNode = PatternMatchingSupport.resolveReference(p, idx, bindingNames, bindings);
                for (N neighbor : PatternMatchingSupport.findDirectNeighbors(view, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    newBindings.put(bindingNames[idx], neighbor);
                    expandChain(view, patterns, bindingNames, newBindings, idx + 1, results);
                }
            }
            case REACHES -> {
                N refNode = PatternMatchingSupport.resolveReference(p, idx, bindingNames, bindings);
                for (N reached : PatternMatchingSupport.findReachable(view, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    newBindings.put(bindingNames[idx], reached);
                    expandChain(view, patterns, bindingNames, newBindings, idx + 1, results);
                }
            }
            case NOT_EXISTS -> {
                boolean exists = p.of().isEmpty()
                                 ? PatternMatchingSupport.existsGlobal(view, p)
                                 : PatternMatchingSupport.existsRelational(view, bindings.get(p.of()), p);
                if (exists) {return;}
                expandChain(view, patterns, bindingNames, bindings, idx + 1, results);
            }
            default -> throw new IllegalStateException("Unexpected pattern kind: " + p.kind());
        }
    }
}
