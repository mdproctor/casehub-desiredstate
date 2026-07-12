package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CbrFaultPolicyTest {

    private record TestSpec(String value) implements NodeSpec {}

    private StubRetriever retriever;
    private StubAdapter adapter;
    private PreferenceProvider prefProvider;
    private CbrFaultPolicy policy;

    @BeforeEach
    void setUp() {
        retriever = new StubRetriever();
        adapter = new StubAdapter();
        prefProvider = scope -> new MapPreferences(Map.of());
        policy = new CbrFaultPolicy(retriever, adapter, prefProvider);
    }

    @Test
    void noCandidates_shouldReturnEmptyMutations() {
        retriever.setResults(List.of());

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void candidateBelowRetrievalThreshold_shouldBeFiltered() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("new"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.9, "case-1"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void candidateBelowAdaptationThreshold_shouldBeFiltered() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("new"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.8, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.4, "case-1"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void successfulAdaptation_shouldProduceMutations() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("fixed"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
    }

    @Test
    void adapterReturnsEmpty_shouldReturnEmptyMutations() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty();
        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        // adapter default is null → returns Optional.empty()

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldSelectHighestConfidenceAdaptation() {
        DesiredStateGraph low = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("low"), NodeType.of("t"), new TestSpec("low"), false));
        DesiredStateGraph high = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("high"), NodeType.of("t"), new TestSpec("high"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(low, 0.9, "case-low", Map.of()),
            new RetrievedConfiguration(high, 0.8, "case-high", Map.of())));
        adapter.setResultForSource("case-low", new AdaptedConfiguration(low, 0.65, "case-low"));
        adapter.setResultForSource("case-high", new AdaptedConfiguration(high, 0.95, "case-high"));

        List<GraphMutation> mutations = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).hasSize(1);
        GraphMutation.AddNode add = (GraphMutation.AddNode) mutations.get(0);
        assertThat(add.node().id()).isEqualTo(NodeId.of("high"));
    }

    @Test
    void perCallPreferenceResolution_changedThreshold() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), false));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        // Default threshold 0.5 → 0.3 below → filtered
        List<GraphMutation> mutations1 = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x"),
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));
        assertThat(mutations1).isEmpty();

        // Lower threshold to 0.2 via preferences
        prefProvider = scope -> new MapPreferences(Map.of(
            DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE.qualifiedName(), "0.2"));
        policy = new CbrFaultPolicy(retriever, adapter, prefProvider);

        List<GraphMutation> mutations2 = policy.onFault(
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x"),
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));
        assertThat(mutations2).hasSize(1);
    }

    @Test
    void contextContainsFaultEvent() {
        List<RetrievalContext> captured = new ArrayList<>();
        ConfigurationRetriever capturingRetriever = (ctx, max) -> {
            captured.add(ctx);
            return List.of();
        };
        CbrFaultPolicy p = new CbrFaultPolicy(capturingRetriever, adapter, prefProvider);

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "boom");
        p.onFault(event, ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).faultEvent()).isEqualTo(event);
        assertThat(captured.get(0).situation()).isNull();
    }

    static class StubRetriever implements ConfigurationRetriever {
        private List<RetrievedConfiguration> results = List.of();
        void setResults(List<RetrievedConfiguration> results) { this.results = results; }
        @Override
        public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
            return results;
        }
    }

    static class StubAdapter implements ConfigurationAdapter {
        private AdaptedConfiguration defaultResult;
        private final Map<String, AdaptedConfiguration> bySourceId = new HashMap<>();

        void setDefaultResult(AdaptedConfiguration result) { this.defaultResult = result; }
        void setResultForSource(String sourceId, AdaptedConfiguration result) { bySourceId.put(sourceId, result); }

        @Override
        public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
            AdaptedConfiguration specific = bySourceId.get(retrieved.sourceId());
            if (specific != null) return Optional.of(specific);
            return Optional.ofNullable(defaultResult);
        }
    }

    static class MapPreferences implements Preferences {
        private final Map<String, String> values;
        MapPreferences(Map<String, String> values) { this.values = values; }

        @Override
        public <T extends SingleValuePreference> T get(PreferenceKey<T> key) {
            String raw = values.get(key.qualifiedName());
            return raw != null ? key.parse(raw) : null;
        }
        @Override
        public <T extends MultiValuePreference> T get(PreferenceKey<T> key, String subKey) { return null; }
        @Override
        public Map<String, Object> asMap() { return Map.copyOf(values); }
    }
}
