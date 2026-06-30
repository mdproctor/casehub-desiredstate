package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesiredStateExecutionRegistryTest {

    private DesiredStateExecutionRegistry registry;
    private DesiredStateGraph graph;

    @BeforeEach
    void setUp() {
        registry = new DesiredStateExecutionRegistry();
        graph = new DefaultDesiredStateGraphFactory().empty();
    }

    @Test
    void registerAndGet() {
        registry.register("exec-1", graph, "tenant-a");

        DesiredStateExecutionContext ctx = registry.get("exec-1");
        assertThat(ctx.graph()).isSameAs(graph);
        assertThat(ctx.tenancyId()).isEqualTo("tenant-a");
    }

    @Test
    void getMissingKeyThrows() {
        assertThatThrownBy(() -> registry.get("nonexistent"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void removeDeletesEntry() {
        registry.register("exec-2", graph, "tenant-b");
        registry.remove("exec-2");

        assertThatThrownBy(() -> registry.get("exec-2"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removeMissingKeyIsSilent() {
        registry.remove("nonexistent");
    }
}
