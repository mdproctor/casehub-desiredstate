package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.render.GridRenderer;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.*;
import io.casehub.desiredstate.example.spatial.world.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ForceDistributionTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;
    private BattlefieldActualStateAdapter adapter;
    private BattlefieldProvisioner provisioner;
    private DefaultDesiredStateGraphFactory factory;
    private TransitionPlanner planner;
    private DistributionGoalCompiler compiler;
    private GridRenderer renderer;

    @BeforeEach
    void setUp() {
        grid = TerrainGrid.create(10, 10,
            (r, c) -> (r >= 4 && r <= 6 && c >= 3 && c <= 7) ? 1 : 0,
            (r, c) -> TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
        adapter = new BattlefieldActualStateAdapter(world);
        provisioner = new BattlefieldProvisioner(world);
        factory = new DefaultDesiredStateGraphFactory();
        planner = new TransitionPlanner();
        compiler = new DistributionGoalCompiler();
        renderer = new GridRenderer(world, 0);
    }

    private void executeAll(TransitionPlan plan, DesiredStateGraph graph,
                           DesiredStateGraph beforeGraph) {
        for (var step : plan.removals()) {
            provisioner.deprovision(step.node(), new DeprovisionContext("test", beforeGraph));
        }
        for (var step : plan.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }
    }

    // --- Layer 2: Ratio distribution ---

    @Test
    void initialFrontierAllocation() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.3)
            .frontierCell(4, 1, 0.2)
            .frontierCell(4, 2, 0.2)
            .frontierCell(4, 3, 0.1)
            .frontierCell(4, 4, 0.2)
            .totalForce(100)
            .zoneName("frontier")
            .build();

        var graph = compiler.compile(blueprint, factory);

        // Verify zone allocation
        var zoneNode = graph.nodes().get(NodeId.of("zone-frontier"));
        assertThat(zoneNode).isNotNull();
        var zoneSpec = (ZoneSpec) zoneNode.spec();
        assertThat(zoneSpec.totalForce()).isEqualTo(100);
        assertThat(zoneSpec.allocation()).hasSize(5);

        // Verify unit strengths match zone ratios
        var unit40 = graph.nodes().get(NodeId.of("unit-cell-4-0"));
        assertThat(((UnitSpec) unit40.spec()).strength()).isEqualTo(30); // 100 * 0.3

        var actual = adapter.readActual(graph, "test");
        var plan = planner.plan(graph, actual);
        executeAll(plan, graph, graph);

        assertThat(world.unitStrength(NodeId.of("unit-cell-4-0"))).isEqualTo(30);
        renderer.printFrame("Initial frontier");
    }

    @Test
    void frontierExpansionRedistributes() {
        // Initial frontier: 5 cells
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.3)
            .frontierCell(4, 1, 0.2)
            .frontierCell(4, 2, 0.2)
            .frontierCell(4, 3, 0.1)
            .frontierCell(4, 4, 0.2)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);

        // Frontier expands — 3 new cells discovered beyond
        var blueprint2 = DistributionBlueprint.builder()
            .frontierCell(5, 1, 0.15)
            .frontierCell(5, 2, 0.20)
            .frontierCell(5, 3, 0.15)
            .frontierCell(5, 4, 0.15)
            .frontierCell(5, 5, 0.10)
            .frontierCell(5, 6, 0.10)
            .frontierCell(5, 7, 0.10)
            .frontierCell(5, 8, 0.05)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph2 = compiler.compile(blueprint2, factory);
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Old units orphaned, new units provisioned
        assertThat(plan2.removals()).isNotEmpty();
        assertThat(plan2.additions()).isNotEmpty();

        executeAll(plan2, graph2, graph1);
        renderer.printFrame("After frontier expansion");
    }

    @Test
    void priorityShiftChangesAllocations_viaRecompilation() {
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);

        // Enemy fortification → double weight on (4,0)
        var blueprint2 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.8)
            .frontierCell(4, 1, 0.2)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph2 = compiler.compile(blueprint2, factory);
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        executeAll(plan2, graph2, graph1);
        assertThat(world.unitStrength(NodeId.of("unit-cell-4-0"))).isEqualTo(80);
        assertThat(world.unitStrength(NodeId.of("unit-cell-4-1"))).isEqualTo(20);
    }

    @Test
    void zoneSplitStructuralChange() {
        // Single zone covering all frontier cells
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.25)
            .frontierCell(4, 1, 0.25)
            .frontierCell(4, 8, 0.25)
            .frontierCell(4, 9, 0.25)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);

        // Split into north-flank and south-flank
        var blueprint2a = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(60)
            .zoneName("north-flank")
            .build();
        var blueprint2b = DistributionBlueprint.builder()
            .frontierCell(4, 8, 0.5)
            .frontierCell(4, 9, 0.5)
            .totalForce(40)
            .zoneName("south-flank")
            .build();
        var graphA = compiler.compile(blueprint2a, factory);
        var graphB = compiler.compile(blueprint2b, factory);
        var graph2 = graphA.overlay(graphB);

        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Old single zone deprovisioned, two new zones provisioned
        // This is a teardown-rebuild — expected finding #2
        assertThat(plan2.removals()).isNotEmpty();
        assertThat(plan2.additions()).isNotEmpty();

        executeAll(plan2, graph2, graph1);
        renderer.printFrame("After zone split");
    }

    // --- Layer 3: Fault policy coupling ---

    @Test
    void faultPolicyInformationGap() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph = compiler.compile(blueprint, factory);
        executeAll(planner.plan(graph, adapter.readActual(graph, "test")), graph, graph);

        // Inject: destroy unit at (4,0)
        world.removeUnit(NodeId.of("unit-cell-4-0"));

        // ActualStateAdapter reports zone as DRIFTED (member unit missing)
        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(NodeId.of("zone-frontier"))).hasValue(NodeStatus.DRIFTED);

        // Fault event for the zone
        var faultEvent = new FaultEvent(
            NodeId.of("zone-frontier"), FaultType.NODE_DEGRADED,
            "Zone member unit missing or strength mismatch");

        // ZoneRebalanceFaultPolicy attempts redistribution
        var policy = new ZoneRebalanceFaultPolicy();
        var mutations = policy.onFault(faultEvent, graph);

        // FINDING: The policy receives (FaultEvent, DesiredStateGraph) but NOT ActualState.
        // It knows the zone is degraded but cannot determine WHICH unit was lost.
        // The FaultEvent carries the zone's NodeId — not the destroyed unit's NodeId.
        // The policy must return empty or make blind guesses.
        assertThat(mutations).as(
            "Fault policy cannot determine which unit was lost — " +
            "FaultPolicy SPI receives (FaultEvent, DesiredStateGraph) but not ActualState. " +
            "Finding #3: fault policy information gap.").isEmpty();

        // Meanwhile, the planner independently detects the missing unit
        var plan = planner.plan(graph, actual);
        assertThat(plan.additions()).anyMatch(step ->
            step.node().id().equals(NodeId.of("unit-cell-4-0")));

        // FINDING: The planner restores the unit with original strength (50).
        // Even if the policy COULD redistribute, it would conflict with the planner's
        // restoration — Finding #9: fault policy / planner conflict.
    }

    // --- Layer 4: Strategic pivot ---

    @Test
    void repeatedLossesUndetected() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph = compiler.compile(blueprint, factory);
        executeAll(planner.plan(graph, adapter.readActual(graph, "test")), graph, graph);

        var policy = new ZoneRebalanceFaultPolicy();
        var allMutations = new ArrayList<List<GraphMutation>>();

        // 3 consecutive cycles of losses
        for (int cycle = 0; cycle < 3; cycle++) {
            world.removeUnit(NodeId.of("unit-cell-4-0"));
            var actual = adapter.readActual(graph, "test");
            var faultEvent = new FaultEvent(
                NodeId.of("zone-frontier"), FaultType.NODE_DEGRADED,
                "Cycle " + cycle + ": zone member destroyed");
            allMutations.add(policy.onFault(faultEvent, graph));

            // Planner restores the unit
            var plan = planner.plan(graph, actual);
            executeAll(plan, graph, graph);
        }

        // FINDING: Each cycle is handled independently. No mechanism detects
        // the pattern "we keep losing units at this position."
        // The fault policy has no memory, no cycle count, no aggregate view.
        // Finding #4: no aggregate subgraph evaluation.
        // Finding #6: no correlated fault detection.
        assertThat(allMutations).as(
            "Three consecutive losses at the same position. " +
            "Each handled independently — no pattern detection, no escalation. " +
            "Findings #4 (no aggregate evaluation) and #6 (no correlated faults).")
            .allSatisfy(mutations -> assertThat(mutations).isEmpty());
    }

    @Test
    void strategicPivotRequiresExternalIntervention() {
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("north-approach")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);
        renderer.printFrame("North approach deployed");

        // Enemy has fortified heavily — the north approach is failing.
        // Decision: pivot to south approach.
        // No SPI can make this decision. It requires:
        // 1. Evaluating aggregate success of "north-approach" subgraph
        // 2. Knowing that "south-approach" is an alternative
        // 3. Making a cost/benefit decision to abandon north for south

        // The GoalCompiler CAN produce the new graph:
        var blueprint2 = DistributionBlueprint.builder()
            .frontierCell(7, 0, 0.5)
            .frontierCell(7, 1, 0.5)
            .totalForce(100)
            .zoneName("south-approach")
            .build();
        var graph2 = compiler.compile(blueprint2, factory);

        // The reconciliation loop CAN transition between them:
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);
        executeAll(plan2, graph2, graph1);

        // But the DECISION to pivot has no home in the current model.
        // FINDING: The pivot requires external intervention — a human or
        // a higher-level system must decide to recompile with a different
        // blueprint. The runtime can execute the transition but cannot
        // initiate it. Finding #5: no strategic alternatives.
        assertThat(graph2.nodes().keySet()).as(
            "Graph transitioned from north to south approach. " +
            "The runtime executed the transition — but the DECISION to pivot " +
            "required external intervention (manual recompilation). " +
            "Finding #5: no strategic alternatives in current model.")
            .noneMatch(id -> id.value().contains("north"));
        renderer.printFrame("After strategic pivot to south");
    }
}
