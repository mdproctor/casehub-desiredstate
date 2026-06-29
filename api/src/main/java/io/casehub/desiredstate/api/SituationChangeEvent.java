package io.casehub.desiredstate.api;

import java.util.Objects;

public record SituationChangeEvent(String tenancyId) {
    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
    }
}
