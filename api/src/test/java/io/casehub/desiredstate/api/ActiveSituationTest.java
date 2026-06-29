package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ActiveSituationTest {

    @Test
    void rejectsNullSituationId() {
        assertThrows(NullPointerException.class, () ->
            new ActiveSituation(null, 0.5, Map.of(), Instant.now()));
    }

    @Test
    void rejectsConfidenceBelowZero() {
        assertThrows(IllegalArgumentException.class, () ->
            new ActiveSituation("test", -0.1, Map.of(), Instant.now()));
    }

    @Test
    void rejectsConfidenceAboveOne() {
        assertThrows(IllegalArgumentException.class, () ->
            new ActiveSituation("test", 1.1, Map.of(), Instant.now()));
    }

    @Test
    void rejectsNaN() {
        assertThrows(IllegalArgumentException.class, () ->
            new ActiveSituation("test", Double.NaN, Map.of(), Instant.now()));
    }

    @Test
    void acceptsValidBoundaries() {
        assertDoesNotThrow(() -> new ActiveSituation("test", 0.0, Map.of(), Instant.now()));
        assertDoesNotThrow(() -> new ActiveSituation("test", 1.0, Map.of(), Instant.now()));
    }

    @Test
    void nullEvidenceDefaultsToEmptyMap() {
        var s = new ActiveSituation("test", 0.5, null, Instant.now());
        assertEquals(Map.of(), s.evidence());
    }

    @Test
    void evidenceIsDefensivelyCopied() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("key", "val");
        var s = new ActiveSituation("test", 0.5, mutable, Instant.now());
        assertThrows(UnsupportedOperationException.class, () -> s.evidence().put("x", "y"));
    }
}
