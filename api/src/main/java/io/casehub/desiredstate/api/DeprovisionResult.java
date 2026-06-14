package io.casehub.desiredstate.api;

public sealed interface DeprovisionResult {
    record Success() implements DeprovisionResult {}
    record Failed(String reason) implements DeprovisionResult {}
}
