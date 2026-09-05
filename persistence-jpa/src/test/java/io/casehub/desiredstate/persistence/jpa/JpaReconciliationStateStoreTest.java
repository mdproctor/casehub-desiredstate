package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JpaReconciliationStateStoreTest {

    @Inject
    JpaReconciliationStateStore store;

    @Inject
    DesiredStateGraphFactory graphFactory;

    @NodeTypeId("jpa-test")
    public record JpaTestSpec(String name, int value) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("jpa-test");
        }
    }

    @BeforeEach
    void clean() {
        store.remove("t1");
        store.remove("t2");
    }

    @Test
    void load_returnsEmpty_whenNothingStored() {
        Optional<DesiredStateGraph> result = store.load("t1");
        assertThat(result).isEmpty();
    }

    @Test
    void store_thenLoad_roundTrips() {
        DesiredNode n1 = new DesiredNode(NodeId.of("a"), new JpaTestSpec("alpha", 1), HumanGating.NONE);
        DesiredNode n2 = new DesiredNode(NodeId.of("b"), new JpaTestSpec("beta", 2), HumanGating.PROVISION_ONLY);
        Dependency dep = new Dependency(NodeId.of("b"), NodeId.of("a"));
        DesiredStateGraph graph = graphFactory.of(List.of(n1, n2), List.of(dep));

        store.store("t1", graph);

        Optional<DesiredStateGraph> loaded = store.load("t1");
        assertThat(loaded).isPresent();
        DesiredStateGraph restored = loaded.get();
        assertThat(restored.nodes()).hasSize(2);
        assertThat(restored.dependencies()).containsExactly(dep);

        DesiredNode restoredA = restored.nodes().get(NodeId.of("a"));
        assertThat(restoredA.spec()).isInstanceOf(JpaTestSpec.class);
        assertThat(((JpaTestSpec) restoredA.spec()).name()).isEqualTo("alpha");
        assertThat(restoredA.humanGating()).isEqualTo(HumanGating.NONE);

        DesiredNode restoredB = restored.nodes().get(NodeId.of("b"));
        assertThat(((JpaTestSpec) restoredB.spec()).value()).isEqualTo(2);
        assertThat(restoredB.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test
    void store_overwritesPreviousValue() {
        DesiredNode n1 = new DesiredNode(NodeId.of("a"), new JpaTestSpec("first", 1), HumanGating.NONE);
        DesiredNode n2 = new DesiredNode(NodeId.of("b"), new JpaTestSpec("second", 2), HumanGating.NONE);
        DesiredStateGraph graph1 = graphFactory.of(List.of(n1), List.of());
        DesiredStateGraph graph2 = graphFactory.of(List.of(n2), List.of());

        store.store("t1", graph1);
        store.store("t1", graph2);

        Optional<DesiredStateGraph> loaded = store.load("t1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().nodes()).hasSize(1);
        assertThat(loaded.get().nodes().containsKey(NodeId.of("b"))).isTrue();
    }

    @Test
    void remove_clearsStoredGraph() {
        DesiredNode n = new DesiredNode(NodeId.of("a"), new JpaTestSpec("x", 0), HumanGating.NONE);
        store.store("t1", graphFactory.of(List.of(n), List.of()));

        store.remove("t1");

        assertThat(store.load("t1")).isEmpty();
    }

    @Test
    void tenantIsolation() {
        DesiredNode n1 = new DesiredNode(NodeId.of("a"), new JpaTestSpec("t1-node", 1), HumanGating.NONE);
        DesiredNode n2 = new DesiredNode(NodeId.of("b"), new JpaTestSpec("t2-node", 2), HumanGating.NONE);
        store.store("t1", graphFactory.of(List.of(n1), List.of()));
        store.store("t2", graphFactory.of(List.of(n2), List.of()));

        assertThat(store.load("t1").get().nodes()).hasSize(1);
        assertThat(store.load("t1").get().nodes().containsKey(NodeId.of("a"))).isTrue();
        assertThat(store.load("t2").get().nodes().containsKey(NodeId.of("b"))).isTrue();

        store.remove("t1");
        assertThat(store.load("t1")).isEmpty();
        assertThat(store.load("t2")).isPresent();
    }
}
