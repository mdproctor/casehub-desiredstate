package io.casehub.desiredstate.annotations.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GraphInvariantEngine {

    public <N> void validate(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                             List<ResolvedInvariant<N>> invariants) {
        List<GraphViolation> violations = new ArrayList<>();
        for (ResolvedInvariant<N> invariant : invariants) {
            switch (invariant) {
                case ResolvedInvariant.ImperativeInvariant<N> imp -> validateImperative(imp, view, violations);
                case ResolvedInvariant.ParameterizedReflectiveInvariant<N> param -> validateParameterized(param, view, violations);
                case ResolvedInvariant.DeclarativeInvariant<N> decl -> validateDeclarative(decl, view, violations);
            }
        }
        if (!violations.isEmpty()) {
            throw new GraphInvariantViolationsException(violations);
        }
    }

    private <N> void validateImperative(ResolvedInvariant.ImperativeInvariant<N> invariant,
                                        io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                        List<GraphViolation> violations) {
        try {
            invariant.validator().accept(view);
        } catch (GraphViolationException gve) {
            violations.add(new GraphViolation(invariant.name(), invariant.name(),
                                              gve.getMessage(), gve.affectedNodes()));
        } catch (RuntimeException e) {
            throw new RuntimeException("Invariant method failed: " + invariant.name(), e);
        }
    }

    private <N> void validateParameterized(ResolvedInvariant.ParameterizedReflectiveInvariant<N> invariant,
                                           io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                           List<GraphViolation> violations) {
        List<PatternParameterDescriptor> patterns   = invariant.patterns();
        String[]                         paramNames = invariant.bindingNames();

        if (hasMatchCardinalityConstraint(patterns)) {
            validateMatchCardinality(invariant.name(),
                                     invariant.method().getDeclaringClass().getName(),
                                     view, patterns, violations);
            return;
        }

        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).kind() == PatternKind.MATCH) {
                matchIndices.add(i);
            }
        }

        List<Map<String, N>> allBindings = PatternEvaluator.evaluate(view, patterns, paramNames);

        Map<List<N>, List<Map<String, N>>> byAnchor = new LinkedHashMap<>();
        for (Map<String, N> binding : allBindings) {
            List<N> anchor = matchIndices.stream()
                                         .map(i -> binding.get(paramNames[i]))
                                         .toList();
            byAnchor.computeIfAbsent(anchor, k -> new ArrayList<>()).add(binding);
        }

        List<List<N>> expectedAnchors = buildExpectedAnchors(view, patterns, matchIndices);

        for (List<N> anchor : expectedAnchors) {
            List<Map<String, N>> expansions = byAnchor.get(anchor);

            boolean cardinalityFailed = checkExpansionCardinality(
                    invariant.name(), invariant.method().getDeclaringClass().getName(),
                    patterns, paramNames, anchor, expansions, violations, view);

            if (!cardinalityFailed) {
                if (expansions == null || expansions.isEmpty()) {
                    String anchorDesc = anchor.stream()
                                              .map(view::nodeId)
                                              .collect(Collectors.joining(", "));
                    violations.add(new GraphViolation(invariant.name(),
                                                      invariant.method().getDeclaringClass().getName(),
                                                      invariant.name() + " violated for [" + anchorDesc + "]",
                                                      anchor.stream().map(view::nodeId).toList()));
                } else {
                    for (Map<String, N> binding : expansions) {
                        List<Object> args = new ArrayList<>(paramNames.length);
                        for (String paramName : paramNames) {
                            args.add(binding.get(paramName));
                        }
                        invokeReflectiveInvariant(invariant, args, violations);
                    }
                }
            }
        }
    }

    private <N> void validateDeclarative(ResolvedInvariant.DeclarativeInvariant<N> invariant,
                                         io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                         List<GraphViolation> violations) {
        List<PatternParameterDescriptor> patterns     = invariant.patterns();
        String[]                         bindingNames = invariant.bindingNames();

        if (hasMatchCardinalityConstraint(patterns)) {
            validateMatchCardinality(invariant.name(), "yaml",
                                     view, patterns, violations);
            return;
        }

        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).kind() == PatternKind.MATCH) {
                matchIndices.add(i);
            }
        }

        List<Map<String, N>> allBindings = PatternEvaluator.evaluate(view, patterns, bindingNames);

        Map<List<N>, List<Map<String, N>>> byAnchor = new LinkedHashMap<>();
        for (Map<String, N> binding : allBindings) {
            List<N> anchor = matchIndices.stream()
                                         .map(i -> binding.get(bindingNames[i]))
                                         .toList();
            byAnchor.computeIfAbsent(anchor, k -> new ArrayList<>()).add(binding);
        }

        List<List<N>> expectedAnchors = buildExpectedAnchors(view, patterns, matchIndices);

        for (List<N> anchor : expectedAnchors) {
            List<Map<String, N>> expansions = byAnchor.get(anchor);

            boolean cardinalityFailed = checkExpansionCardinality(
                    invariant.name(), "yaml",
                    patterns, bindingNames, anchor, expansions, violations, view);

            if (!cardinalityFailed) {
                if (expansions == null || expansions.isEmpty()) {
                    String anchorDesc = anchor.stream()
                                              .map(view::nodeId)
                                              .collect(Collectors.joining(", "));
                    String message = invariant.messageTemplate() != null
                                     ? resolveMatchTemplate(invariant.messageTemplate(), anchor, matchIndices, bindingNames, view)
                                     : invariant.name() + " violated for [" + anchorDesc + "]";
                    violations.add(new GraphViolation(invariant.name(), "yaml",
                                                      message, anchor.stream().map(view::nodeId).toList()));
                }
            }
        }
    }

    private <N> String resolveMatchTemplate(String template, List<N> anchor,
                                            List<Integer> matchIndices, String[] bindingNames,
                                            io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view) {
        String resolved = template;
        for (int i = 0; i < matchIndices.size(); i++) {
            N      node    = anchor.get(i);
            String binding = bindingNames[matchIndices.get(i)];
            resolved = resolved.replace("${match." + binding + ".id}", view.nodeId(node));
            resolved = resolved.replace("${match." + binding + ".type}", view.nodeType(node));
        }
        return resolved;
    }

    private <N> boolean checkExpansionCardinality(String invariantName, String sourceClass,
                                                  List<PatternParameterDescriptor> patterns,
                                                  String[] bindingNames, List<N> anchor,
                                                  List<Map<String, N>> expansions,
                                                  List<GraphViolation> violations,
                                                  io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view) {
        boolean failed = false;
        for (int i = 0; i < patterns.size(); i++) {
            PatternParameterDescriptor p = patterns.get(i);
            if (p.kind() == PatternKind.MATCH || p.kind() == PatternKind.NOT_EXISTS) {continue;}
            if (!p.hasCardinalityConstraint()) {continue;}
            final int idx = i;
            long bindingCount = expansions == null ? 0
                                                   : expansions.stream()
                                                               .map(b -> b.get(bindingNames[idx]))
                                                               .distinct()
                                                               .count();
            String anchorDesc = anchor.stream()
                                      .map(view::nodeId)
                                      .collect(Collectors.joining(", "));
            if (bindingCount < p.effectiveMinCount()) {
                violations.add(new GraphViolation(invariantName, sourceClass,
                                                  invariantName + " for [" + anchorDesc + "]: expected at least "
                                                  + p.effectiveMinCount() + " '" + p.nodeType()
                                                  + "' binding(s), found " + bindingCount,
                                                  anchor.stream().map(view::nodeId).toList()));
                failed = true;
            }
            if (bindingCount > p.effectiveMaxCount()) {
                violations.add(new GraphViolation(invariantName, sourceClass,
                                                  invariantName + " for [" + anchorDesc + "]: expected at most "
                                                  + p.effectiveMaxCount() + " '" + p.nodeType()
                                                  + "' binding(s), found " + bindingCount,
                                                  anchor.stream().map(view::nodeId).toList()));
                failed = true;
            }
        }
        return failed;
    }

    private boolean hasMatchCardinalityConstraint(List<PatternParameterDescriptor> patterns) {
        return patterns.stream()
                       .anyMatch(p -> p.kind() == PatternKind.MATCH && p.hasCardinalityConstraint());
    }

    private <N> void validateMatchCardinality(String invariantName, String sourceClass,
                                              io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                              List<PatternParameterDescriptor> patterns,
                                              List<GraphViolation> violations) {
        for (PatternParameterDescriptor p : patterns) {
            if (p.kind() != PatternKind.MATCH) {continue;}
            long count = countMatchingNodes(view, p.nodeType());
            if (count < p.effectiveMinCount()) {
                violations.add(new GraphViolation(invariantName, sourceClass,
                                                  invariantName + ": expected at least " + p.effectiveMinCount()
                                                  + " node(s) of type '" + p.nodeType() + "', found " + count,
                                                  List.of()));
            }
            if (count > p.effectiveMaxCount()) {
                violations.add(new GraphViolation(invariantName, sourceClass,
                                                  invariantName + ": expected at most " + p.effectiveMaxCount()
                                                  + " node(s) of type '" + p.nodeType() + "', found " + count,
                                                  List.of()));
            }
        }
    }

    private <N> long countMatchingNodes(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                        String nodeType) {
        if ("*".equals(nodeType)) {return view.nodes().size();}
        return view.nodes().values().stream()
                   .filter(n -> view.nodeType(n).equals(nodeType))
                   .count();
    }

    private <N> List<List<N>> buildExpectedAnchors(io.casehub.desiredstate.annotations.runtime.graph.GraphView<N> view,
                                                   List<PatternParameterDescriptor> patterns,
                                                   List<Integer> matchIndices) {
        List<List<N>> matchSets = new ArrayList<>();
        for (int i : matchIndices) {
            PatternParameterDescriptor p = patterns.get(i);
            if ("*".equals(p.nodeType())) {
                matchSets.add(new ArrayList<>(view.nodes().values()));
            } else {
                matchSets.add(view.nodes().values().stream()
                                  .filter(n -> view.nodeType(n).equals(p.nodeType()))
                                  .toList());
            }
        }
        if (matchSets.isEmpty() || matchSets.stream().anyMatch(List::isEmpty)) {
            return List.of();
        }
        return PatternMatchingSupport.crossProduct(matchSets);
    }

    private <N> void invokeReflectiveInvariant(ResolvedInvariant.ParameterizedReflectiveInvariant<N> invariant,
                                               List<Object> args, List<GraphViolation> violations) {
        try {
            if (invariant.instance() != null) {
                invariant.method().invoke(invariant.instance(), args.toArray());
            } else {
                invariant.method().invoke(null, args.toArray());
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof GraphViolationException gve) {
                violations.add(new GraphViolation(invariant.name(),
                                                  invariant.method().getDeclaringClass().getName(),
                                                  gve.getMessage(), gve.affectedNodes()));
            } else {
                throw new RuntimeException("Invariant method failed: "
                                           + invariant.name(), e.getCause());
            }
        } catch (Exception e) {
            throw new RuntimeException("Invariant method invocation failed: "
                                       + invariant.name(), e);
        }
    }
}
