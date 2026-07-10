package io.casehub.desiredstate.api;

import java.util.Set;

public interface ActualStateAdapterRouter {
    ActualState readActual(DesiredStateGraph desired, String tenancyId);
    Set<NodeType> allHandledTypes();
}
