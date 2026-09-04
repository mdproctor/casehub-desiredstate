package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.GraphMutation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class GraphRuleEngine {

    private static final int MAX_ITERATIONS = 100;

    public <N> io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> evaluate(
            io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> view,
            List<ResolvedRule<N>> rules) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            List<RuleContribution<N>> contributions = new ArrayList<>();

            for (ResolvedRule<N> rule : rules) {
                List<GraphMutation<N>> mutations = evaluateRule(rule, view);
                if (!mutations.isEmpty()) {
                    contributions.add(new RuleContribution<>(rule.name(), mutations));
                }
            }

            List<GraphMutation<N>> allMutations = contributions.stream()
                                                               .flatMap(c -> c.mutations().stream()).toList();

            if (allMutations.isEmpty()) {
                return view;
            }

            List<GraphMutation<N>> deduped = deduplicateMutations(allMutations);
            detectNodeConflicts(deduped);
            detectEdgeConflicts(deduped);
            List<GraphMutation<N>> effective = filterNoOps(deduped, view);
            if (effective.isEmpty()) {
                return view;
            }
            view = applyMutations(view, sortByType(effective), contributions);
        }

        io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> finalView = view;
        List<ResolvedRule<N>> activeRules = rules.stream()
                                                 .filter(r -> !evaluateRule(r, finalView).isEmpty())
                                                 .toList();
        throw new GraphRuleNonConvergenceException(
                activeRules.isEmpty() ? rules : activeRules, MAX_ITERATIONS);
    }

    private <N> List<GraphMutation<N>> evaluateRule(ResolvedRule<N> rule,
                                                    io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> view) {
        return switch (rule) {
            case ResolvedRule.ImperativeRule<N> imp -> {
                List<GraphMutation<N>> result = imp.evaluator().apply(view);
                yield result != null ? result : List.of();
            }
            case ResolvedRule.ParameterizedRule<N> param -> evaluateParameterized(param, view);
            case ResolvedRule.DeclarativeRule<N> decl -> evaluateDeclarative(decl, view);
        };
    }

    private <N> List<GraphMutation<N>> evaluateParameterized(ResolvedRule.ParameterizedRule<N> rule,
                                                             io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> view) {
        List<GraphMutation<N>>           allMutations = new ArrayList<>();
        List<PatternParameterDescriptor> patterns     = rule.patterns();
        String[]                         paramNames   = rule.bindingNames();

        List<Map<String, N>> allBindings = PatternEvaluator.evaluate(view, patterns, paramNames);

        for (Map<String, N> binding : allBindings) {
            List<Object> args = new ArrayList<>(paramNames.length);
            for (String paramName : paramNames) {
                args.add(binding.get(paramName));
            }
            invokeRule(rule, args, allMutations);
        }

        return allMutations;
    }

    private <N> List<GraphMutation<N>> evaluateDeclarative(ResolvedRule.DeclarativeRule<N> rule,
                                                           io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> view) {
        List<GraphMutation<N>> allMutations = new ArrayList<>();
        List<Map<String, N>> allBindings =
                PatternEvaluator.evaluate(view, rule.patterns(), rule.bindingNames());
        for (Map<String, N> binding : allBindings) {
            List<GraphMutation<N>> mutations = rule.actionEvaluator().apply(binding);
            if (mutations != null && !mutations.isEmpty()) {
                allMutations.addAll(mutations);
            }
        }
        return allMutations;
    }

    private <N> List<GraphMutation<N>> deduplicateMutations(List<GraphMutation<N>> mutations) {
        return new ArrayList<>(new LinkedHashSet<>(mutations));
    }

    private <N> void detectNodeConflicts(List<GraphMutation<N>> mutations) {
        Map<String, GraphMutation<N>> byNodeId = new LinkedHashMap<>();
        for (GraphMutation<N> m : mutations) {
            String target = m.targetNodeId();
            if (target == null) {continue;}
            GraphMutation<N> existing = byNodeId.get(target);
            if (existing != null && !existing.equals(m)) {
                throw new ConflictingMutationException(target, existing, m);
            }
            byNodeId.put(target, m);
        }
    }

    private <N> void detectEdgeConflicts(List<GraphMutation<N>> mutations) {
        Map<Map.Entry<String, String>, GraphMutation<N>> addEdges    = new HashMap<>();
        Map<Map.Entry<String, String>, GraphMutation<N>> removeEdges = new HashMap<>();
        for (GraphMutation<N> m : mutations) {
            switch (m) {
                case GraphMutation.AddEdge<N> add -> addEdges.put(Map.entry(add.from(), add.to()), m);
                case GraphMutation.RemoveEdge<N> rem -> removeEdges.put(Map.entry(rem.from(), rem.to()), m);
                default -> {}
            }
        }
        for (var entry : addEdges.entrySet()) {
            GraphMutation<N> remove = removeEdges.get(entry.getKey());
            if (remove != null) {
                throw new ConflictingMutationException(
                        entry.getKey().getKey(), entry.getValue(), remove);
            }
        }
    }

    private <N> List<GraphMutation<N>> filterNoOps(List<GraphMutation<N>> mutations,
                                                   io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> view) {
        return mutations.stream().filter(m -> switch (m) {
            case GraphMutation.AddNode<N> add -> {
                N existing = view.node(add.id());
                yield existing == null || !existing.equals(add.node());
            }
            case GraphMutation.RemoveNode<N> rem -> view.node(rem.id()) != null;
            case GraphMutation.UpdateNode<N> upd -> {
                N existing = view.node(upd.id());
                yield existing == null || !existing.equals(upd.adaptedNode());
            }
            case GraphMutation.AddEdge<N> add -> !view.dependenciesOf(add.from()).contains(add.to());
            case GraphMutation.RemoveEdge<N> rem -> view.dependenciesOf(rem.from()).contains(rem.to());
        }).toList();
    }

    private <N> io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> applyMutations(
            io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N> view,
            List<GraphMutation<N>> sorted, List<RuleContribution<N>> contributions) {
        try {
            for (GraphMutation<N> m : sorted) {
                view = view.withMutation(m);
            }
            return view;
        } catch (io.casehub.desiredstate.annotations.runtime.graph.GraphCycleException e) {
            List<String> ruleNames = contributions.stream()
                                                  .map(RuleContribution::ruleName).toList();
            throw new GraphRuleCycleException(ruleNames, e.getCycle());
        }
    }

    private <N> List<GraphMutation<N>> sortByType(List<GraphMutation<N>> mutations) {
        return mutations.stream().sorted(Comparator.comparingInt(m -> switch (m) {
            case GraphMutation.AddNode<?> ignored -> 0;
            case GraphMutation.UpdateNode<?> ignored -> 1;
            case GraphMutation.RemoveEdge<?> ignored -> 2;
            case GraphMutation.RemoveNode<?> ignored -> 3;
            case GraphMutation.AddEdge<?> ignored -> 4;
        })).toList();
    }

    @SuppressWarnings("unchecked")
    private <N> void invokeRule(ResolvedRule.ParameterizedRule<N> rule, List<Object> args,
                                List<GraphMutation<N>> allMutations) {
        try {
            var result = (List<GraphMutation<N>>) rule.method().invoke(rule.instance(), args.toArray());
            if (result != null && !result.isEmpty()) {
                allMutations.addAll(result);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {throw re;}
            throw new RuntimeException("Rule " + rule.name() + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Rule " + rule.name() + " inaccessible", e);
        }
    }

    private record RuleContribution<N>(String ruleName, List<GraphMutation<N>> mutations) {}
}
