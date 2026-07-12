package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CbrSituationRecompilerTest {

    private record TestSpec(String value) implements NodeSpec {}

    private CbrFaultPolicyTest.StubRetriever retriever;
    private CbrFaultPolicyTest.StubAdapter adapter;
    private PreferenceProvider prefProvider;
    private CbrSituationRecompiler recompiler;

    private final ActiveSituation situation = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);

    @BeforeEach
    void setUp() {
        retriever = new CbrFaultPolicyTest.StubRetriever();
        adapter = new CbrFaultPolicyTest.StubAdapter();
        prefProvider = scope -> new CbrFaultPolicyTest.MapPreferences(Map.of());
        recompiler = new CbrSituationRecompiler(retriever, adapter, prefProvider);
    }

    @Test
    void noCandidates_shouldReturnEmpty() {
        retriever.setResults(List.of());

        Optional<CompilationResult> result = recompiler.recompile(
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void successfulAdaptation_shouldReturnCompilationResult() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("fixed"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile(
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CompilationResult.SingleGraph.class);
        CompilationResult.SingleGraph sg = (CompilationResult.SingleGraph) result.get();
        assertThat(sg.graph().nodes()).containsKey(NodeId.of("n1"));
    }

    @Test
    void shouldHaveMaxIntPriority() {
        assertThat(recompiler.priority()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void actualStateIsPassedToRetrievalContext() {
        ActualState actual = new ActualState(Map.of(NodeId.of("x"), NodeStatus.PRESENT));

        List<RetrievalContext> captured = new ArrayList<>();
        ConfigurationRetriever capturingRetriever = (ctx, max) -> {
            captured.add(ctx);
            return List.of();
        };
        CbrSituationRecompiler r = new CbrSituationRecompiler(capturingRetriever, adapter, prefProvider);

        r.recompile(ImmutableDesiredStateGraph.empty(), actual, situation, null);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).actualState()).isSameAs(actual);
        assertThat(captured.get(0).situation()).isEqualTo(situation);
        assertThat(captured.get(0).faultEvent()).isNull();
    }

    @Test
    void belowRetrievalThreshold_shouldReturnEmpty() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.9, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile(
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void belowAdaptationThreshold_shouldReturnEmpty() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.8, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.4, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile(
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }
}
