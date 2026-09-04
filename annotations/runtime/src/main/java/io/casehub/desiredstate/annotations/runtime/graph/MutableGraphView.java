package io.casehub.desiredstate.annotations.runtime.graph;

import io.casehub.desiredstate.api.GraphMutation;

public interface MutableGraphView<N> extends GraphView<N> {
    MutableGraphView<N> withMutation(GraphMutation<N> mutation);
}
