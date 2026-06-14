package io.casehub.desiredstate.api;

import io.smallrye.mutiny.Multi;

public interface EventSource {
    Multi<StateEvent> stream();
}
