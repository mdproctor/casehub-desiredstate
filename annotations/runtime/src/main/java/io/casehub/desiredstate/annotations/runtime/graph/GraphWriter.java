package io.casehub.desiredstate.annotations.runtime.graph;

import io.casehub.desiredstate.api.GraphMutation;

public interface GraphWriter<G, N> {
    G applyMutation(G graph, GraphMutation<N> mutation) throws GraphCycleException;
}
