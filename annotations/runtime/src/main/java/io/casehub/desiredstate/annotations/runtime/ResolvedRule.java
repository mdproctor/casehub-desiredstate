package io.casehub.desiredstate.annotations.runtime;

import java.lang.reflect.Method;
import java.util.List;

public sealed interface ResolvedRule<N> {

    String name();

    List<PatternParameterDescriptor> patterns();

    String[] bindingNames();

    record ImperativeRule<N>(String name,
                             java.util.function.Function<io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView<N>,
                                                                List<io.casehub.desiredstate.api.GraphMutation<N>>> evaluator) implements ResolvedRule<N> {
        @Override
        public List<PatternParameterDescriptor> patterns() {return List.of();}

        @Override
        public String[] bindingNames() {return new String[0];}
    }

    record ParameterizedRule<N>(String name, Method method, Object instance,
                                List<PatternParameterDescriptor> patterns) implements ResolvedRule<N> {
        @Override
        public String[] bindingNames() {
            return PatternMatchingSupport.getParameterNames(method);
        }
    }

    record DeclarativeRule<N>(String name, List<PatternParameterDescriptor> patterns,
                              String[] bindingNames,
                              java.util.function.Function<java.util.Map<String, N>,
                                                                 java.util.List<io.casehub.desiredstate.api.GraphMutation<N>>> actionEvaluator)
            implements ResolvedRule<N> {
    }
}
