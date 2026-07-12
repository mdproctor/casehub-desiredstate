package io.casehub.desiredstate.api;

public record CbrConfiguration(
    double minimumRetrievalConfidence,
    double minimumAdaptationConfidence,
    int maxCandidates
) {
    public CbrConfiguration {
        if (Double.isNaN(minimumRetrievalConfidence) || minimumRetrievalConfidence < 0.0 || minimumRetrievalConfidence > 1.0) {
            throw new IllegalArgumentException("minimumRetrievalConfidence must be 0.0-1.0");
        }
        if (Double.isNaN(minimumAdaptationConfidence) || minimumAdaptationConfidence < 0.0 || minimumAdaptationConfidence > 1.0) {
            throw new IllegalArgumentException("minimumAdaptationConfidence must be 0.0-1.0");
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates must be >= 1");
        }
    }
}
