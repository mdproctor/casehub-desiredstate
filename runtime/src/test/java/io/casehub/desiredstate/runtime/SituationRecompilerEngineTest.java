package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SituationRecompilerEngineTest {

    private final DesiredStateGraph graph = ImmutableDesiredStateGraph.empty();
    private final ActualState actual = new ActualState(Map.of());
    private final ActiveSituation situation = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);

    private record TestSpec(String value) implements NodeSpec {}

    @Test
    void emptyRecompilerList_shouldReturnEmpty() {
        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of());
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);
        assertThat(result).isEmpty();
    }

    @Test
    void singleRecompiler_returnsNonEmpty_shouldReturnResult() {
        DesiredStateGraph newGraph = graph.withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), false));
        SituationRecompiler recompiler = (c, a, s, f) -> Optional.of(CompilationResult.single(newGraph));

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(recompiler));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isPresent();
    }

    @Test
    void multipleRecompilers_firstReturnsEmpty_secondReturnsResult() {
        SituationRecompiler empty = (c, a, s, f) -> Optional.empty();
        SituationRecompiler hasResult = (c, a, s, f) -> Optional.of(CompilationResult.single(graph));

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(empty, hasResult));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isPresent();
    }

    @Test
    void allRecompilersReturnEmpty_shouldReturnEmpty() {
        SituationRecompiler e1 = (c, a, s, f) -> Optional.empty();
        SituationRecompiler e2 = (c, a, s, f) -> Optional.empty();

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(e1, e2));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldRespectPriorityOrdering() {
        DesiredStateGraph lowGraph = graph.withNode(
            new DesiredNode(NodeId.of("low"), NodeType.of("test"), new TestSpec("low"), false));
        DesiredStateGraph highGraph = graph.withNode(
            new DesiredNode(NodeId.of("high"), NodeType.of("test"), new TestSpec("high"), false));

        SituationRecompiler lowPriority = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(lowGraph));
            }
            @Override
            public int priority() { return 0; }
        };

        SituationRecompiler highPriority = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(highGraph));
            }
            @Override
            public int priority() { return Integer.MAX_VALUE; }
        };

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(highPriority, lowPriority));
        Optional<CompilationResult> result = engine.recompile(graph, actual, situation, null);

        assertThat(result).isPresent();
        CompilationResult.SingleGraph sg = (CompilationResult.SingleGraph) result.get();
        assertThat(sg.graph().nodes()).containsKey(NodeId.of("low"));
    }

    @Test
    void firstMatchWins_shouldNotCallSubsequentRecompilers() {
        List<String> callOrder = new ArrayList<>();

        SituationRecompiler first = (c, a, s, f) -> {
            callOrder.add("first");
            return Optional.of(CompilationResult.single(graph));
        };
        SituationRecompiler second = (c, a, s, f) -> {
            callOrder.add("second");
            return Optional.of(CompilationResult.single(graph));
        };

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(first, second));
        engine.recompile(graph, actual, situation, null);

        assertThat(callOrder).containsExactly("first");
    }
}
