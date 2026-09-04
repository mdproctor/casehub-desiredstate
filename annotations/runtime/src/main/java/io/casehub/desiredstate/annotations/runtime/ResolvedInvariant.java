package io.casehub.desiredstate.annotations.runtime;

import java.lang.reflect.Method;
import java.util.List;

public sealed interface ResolvedInvariant<N> {

    String name();

    List<PatternParameterDescriptor> patterns();

    String[] bindingNames();

    record ImperativeInvariant<N>(String name,
                                  java.util.function.Consumer<io.casehub.desiredstate.annotations.runtime.graph.GraphView<N>> validator)
            implements ResolvedInvariant<N> {
        @Override
        public List<PatternParameterDescriptor> patterns() {return List.of();}

        @Override
        public String[] bindingNames() {return new String[0];}
    }

    record ParameterizedReflectiveInvariant<N>(String name, Method method, Object instance,
                                               List<PatternParameterDescriptor> patterns)
            implements ResolvedInvariant<N> {
        @Override
        public String[] bindingNames() {
            return PatternMatchingSupport.getParameterNames(method);
        }
    }

    record DeclarativeInvariant<N>(String name, List<PatternParameterDescriptor> patterns,
                                   String[] bindingNames, String messageTemplate)
            implements ResolvedInvariant<N> {
    }
}
