package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Multi;

public interface MergedEventSource {
    Multi<StateEvent> stream();
}
