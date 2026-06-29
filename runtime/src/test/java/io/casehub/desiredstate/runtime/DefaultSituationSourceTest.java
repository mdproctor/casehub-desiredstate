package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.SituationSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefaultSituationSourceTest {

    @Test
    void returnsEmptyList() {
        SituationSource source = new DefaultSituationSource();
        assertTrue(source.activeSituations("tenant-1").isEmpty());
    }

    @Test
    void acceptsAnyTenantId() {
        SituationSource source = new DefaultSituationSource();
        assertDoesNotThrow(() -> source.activeSituations("any-tenant"));
    }
}
