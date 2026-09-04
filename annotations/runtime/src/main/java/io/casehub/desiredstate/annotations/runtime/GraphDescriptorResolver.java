package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class GraphDescriptorResolver {

    private GraphDescriptorResolver() {}

    @SuppressWarnings("unchecked")
    public static List<ResolvedRule<io.casehub.desiredstate.api.DesiredNode>> resolveRules(List<GraphRuleDescriptor> descriptors) {
        List<ResolvedRule<io.casehub.desiredstate.api.DesiredNode>> rules       = new ArrayList<>();
        ClassLoader                                                 classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphRuleDescriptor grd : descriptors) {
            try {
                Class<?> ruleClass = classLoader.loadClass(grd.sourceClassName());
                Object ruleInstance = java.lang.reflect.Modifier.isInterface(ruleClass.getModifiers())
                                      ? null : ruleClass.getDeclaredConstructor().newInstance();
                Method ruleMethod = findRuleMethod(ruleClass, grd);
                if (grd.imperative()) {
                    Method m    = ruleMethod;
                    Object inst = ruleInstance;
                    java.util.function.Function<io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<io.casehub.desiredstate.api.DesiredNode>,
                                                       java.util.List<io.casehub.desiredstate.api.GraphMutation<io.casehub.desiredstate.api.DesiredNode>>> evaluator = view -> {
                        try {
                            io.casehub.desiredstate.api.DesiredStateGraph graph  = ((io.casehub.desiredstate.annotations.runtime.DesiredStateGraphView) view).graph();
                            var                                           result = (java.util.List<io.casehub.desiredstate.api.GraphMutation<io.casehub.desiredstate.api.DesiredNode>>) m.invoke(inst, graph);
                            return result != null ? result : List.of();
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            if (e.getCause() instanceof RuntimeException re) {throw re;}
                            throw new RuntimeException("Rule " + grd.methodName() + " failed", e.getCause());
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("Rule " + grd.methodName() + " inaccessible", e);
                        }
                    };
                    rules.add(new ResolvedRule.ImperativeRule<>(grd.methodName(), evaluator));
                } else {
                    rules.add(new ResolvedRule.ParameterizedRule<>(
                            grd.methodName(), ruleMethod, ruleInstance, grd.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph rule: " + grd.methodName(), e);
            }
        }
        return rules;
    }

    public static List<ResolvedInvariant<io.casehub.desiredstate.api.DesiredNode>> resolveInvariants(List<GraphInvariantDescriptor> descriptors) {
        List<ResolvedInvariant<io.casehub.desiredstate.api.DesiredNode>> invariants  = new ArrayList<>();
        ClassLoader                                                      classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphInvariantDescriptor gid : descriptors) {
            try {
                Class<?> cls = classLoader.loadClass(gid.sourceClassName());
                Object instance = java.lang.reflect.Modifier.isInterface(cls.getModifiers())
                                  ? null : cls.getDeclaredConstructor().newInstance();
                Method method = findInvariantMethod(cls, gid);
                if (gid.imperative()) {
                    Method m    = method;
                    Object inst = instance;
                    java.util.function.Consumer<io.casehub.desiredstate.annotations.runtime.graph.GraphView<io.casehub.desiredstate.api.DesiredNode>> validator = view -> {
                        try {
                            io.casehub.desiredstate.api.DesiredStateGraph graph = ((io.casehub.desiredstate.annotations.runtime.DesiredStateGraphView) view).graph();
                            if (inst != null) {m.invoke(inst, graph);} else {m.invoke(null, graph);}
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            if (e.getCause() instanceof GraphViolationException gve) {throw gve;}
                            if (e.getCause() instanceof RuntimeException re) {throw re;}
                            throw new RuntimeException("Invariant " + gid.methodName() + " failed", e.getCause());
                        } catch (Exception e) {
                            throw new RuntimeException("Invariant " + gid.methodName() + " failed", e);
                        }
                    };
                    invariants.add(new ResolvedInvariant.ImperativeInvariant<>(gid.methodName(), validator));
                } else {
                    invariants.add(new ResolvedInvariant.ParameterizedReflectiveInvariant<>(
                            gid.methodName(), method, instance, gid.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph invariant: " + gid.methodName(), e);
            }
        }
        return invariants;
    }

    private static Method findRuleMethod(Class<?> cls, GraphRuleDescriptor grd)
            throws NoSuchMethodException {
        if (grd.imperative()) {
            return cls.getMethod(grd.methodName(), DesiredStateGraph.class);
        }
        Class<?>[] paramTypes = new Class<?>[grd.patterns().size()];
        for (int i = 0; i < grd.patterns().size(); i++) {
            paramTypes[i] = grd.patterns().get(i).kind() == PatternKind.NOT_EXISTS
                    ? Void.class : DesiredNode.class;
        }
        return cls.getMethod(grd.methodName(), paramTypes);
    }

    private static Method findInvariantMethod(Class<?> cls, GraphInvariantDescriptor gid)
            throws NoSuchMethodException {
        if (gid.imperative()) {
            return cls.getMethod(gid.methodName(), DesiredStateGraph.class);
        }
        Class<?>[] paramTypes = new Class<?>[gid.patterns().size()];
        for (int i = 0; i < gid.patterns().size(); i++) {
            paramTypes[i] = gid.patterns().get(i).kind() == PatternKind.NOT_EXISTS
                    ? Void.class : DesiredNode.class;
        }
        return cls.getMethod(gid.methodName(), paramTypes);
    }
}
