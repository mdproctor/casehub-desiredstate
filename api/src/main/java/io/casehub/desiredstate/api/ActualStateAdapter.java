package io.casehub.desiredstate.api;

import java.util.Set;

public interface ActualStateAdapter {
    ActualState readActual(DesiredStateGraph desired, String tenancyId);
    Set<NodeType> handledTypes();
}
