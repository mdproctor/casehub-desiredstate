package io.casehub.desiredstate.annotations.runtime;

import java.util.List;

public record GraphViolation(
        String invariantName,
        String sourceClassName,
        String message,
        List<String> affectedNodes) {}
