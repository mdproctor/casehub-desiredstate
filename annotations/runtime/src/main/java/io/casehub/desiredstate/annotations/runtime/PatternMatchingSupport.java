package io.casehub.desiredstate.annotations.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PatternMatchingSupport {

    private PatternMatchingSupport() {}

    public static <N> N resolveReference(PatternParameterDescriptor p, int paramIndex,
                                         String[] paramNames, Map<String, N> bindings) {
        if (!p.of().isEmpty()) {
            return bindings.get(p.of());
        }
        for (int i = paramIndex - 1; i >= 0; i--) {
            N prev = bindings.get(paramNames[i]);
            if (prev != null) {return prev;}
        }
        return null;
    }

    public static <N> List<N> findDirectNeighbors(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                                  N refNode, PatternParameterDescriptor p) {
        boolean wildcard = "*".equals(p.nodeType());
        Set<String> neighbors = p.direction() == Direction.DEPENDENCIES
                                ? view.dependenciesOf(view.nodeId(refNode))
                                : view.dependentsOf(view.nodeId(refNode));
        return neighbors.stream()
                        .map(view::node)
                        .filter(n -> n != null && (wildcard || view.nodeType(n).equals(p.nodeType())))
                        .toList();
    }

    public static <N> List<N> findReachable(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                            N refNode, PatternParameterDescriptor p) {
        boolean            wildcard = "*".equals(p.nodeType());
        List<N>            found    = new ArrayList<>();
        Set<String>        visited  = new HashSet<>();
        ArrayDeque<String> queue    = new ArrayDeque<>();
        String             startId  = view.nodeId(refNode);
        queue.add(startId);
        visited.add(startId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> neighbors = p.direction() == Direction.DEPENDENCIES
                                    ? view.dependenciesOf(current)
                                    : view.dependentsOf(current);
            for (String neighbor : neighbors) {
                if (visited.add(neighbor)) {
                    N node = view.node(neighbor);
                    if (node != null && (wildcard || view.nodeType(node).equals(p.nodeType()))) {
                        found.add(node);
                    }
                    queue.add(neighbor);
                }
            }
        }
        return found;
    }

    public static <N> boolean existsGlobal(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                           PatternParameterDescriptor p) {
        if ("*".equals(p.nodeType())) {
            return !view.nodes().isEmpty();
        }
        return view.nodes().values().stream()
                   .anyMatch(n -> view.nodeType(n).equals(p.nodeType()));
    }

    public static <N> boolean existsRelational(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                               N refNode, PatternParameterDescriptor p) {
        boolean wildcard = "*".equals(p.nodeType());
        Set<String> neighbors = p.direction() == Direction.DEPENDENCIES
                                ? view.dependenciesOf(view.nodeId(refNode))
                                : view.dependentsOf(view.nodeId(refNode));
        return neighbors.stream()
                        .map(view::node)
                        .anyMatch(n -> n != null && (wildcard || view.nodeType(n).equals(p.nodeType())));
    }

    public static String[] getParameterNames(java.lang.reflect.Method method) {
        var      params = method.getParameters();
        String[] names  = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            names[i] = params[i].getName();
        }
        return names;
    }

    public static <N> List<List<N>> crossProduct(List<List<N>> sets) {
        List<List<N>> result = new ArrayList<>();
        result.add(List.of());
        for (List<N> set : sets) {
            List<List<N>> newResult = new ArrayList<>();
            for (List<N> existing : result) {
                for (N item : set) {
                    List<N> combined = new ArrayList<>(existing);
                    combined.add(item);
                    newResult.add(combined);
                }
            }
            result = newResult;
        }
        return result;
    }
}
