package io.casehub.desiredstate.api;

public sealed interface ProvisionResult {
    record Success() implements ProvisionResult {}
    record Failed(String reason) implements ProvisionResult {}
}
