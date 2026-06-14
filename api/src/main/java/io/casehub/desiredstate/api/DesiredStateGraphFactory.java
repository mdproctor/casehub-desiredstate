package io.casehub.desiredstate.api;

import java.util.Collection;

public interface DesiredStateGraphFactory {
    DesiredStateGraph empty();
    DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps);
}
