# Spatial/Vector POC

Research example evaluating whether the desired-state graph model can represent
spatial domains -- terrain grids, fog of war, force distribution, and zone-based
fault response. Three scenarios test different spatial patterns against the
graph primitives.

## What It Demonstrates

| Runtime Feature | How It's Used |
|----------------|---------------|
| `GoalCompiler` (x3) | Three compilers for three spatial scenarios: defense, attack, distribution |
| `FaultPolicy` | `ZoneRebalanceFaultPolicy` redistributes forces when units are lost -- graph mutation via `UpdateNode` |
| `NodeProvisioner` | `BattlefieldProvisioner` provisions cells, units, scouts, and zones |
| `ActualStateAdapter` | `BattlefieldActualStateAdapter` reports unit and zone health |
| Graph as spatial model | Cells are nodes, adjacency is dependencies, zones aggregate cells |
| `GraphMutation.UpdateNode` | Fault policy replaces zone allocation and unit strength in-place |

## Domain

A 10x10 terrain grid with height, terrain type (OPEN, FOREST, MOUNTAIN, WATER),
and fog of war. Three scenarios model different military operations:

**Scenario 1: Defense Posture** -- Place scouts at observation points and
distribute forces across defensive zones. Scouts depend on cells; zones
aggregate multiple cells with allocation ratios.

**Scenario 2: Attack Waypoints** -- Plan an attack route through a chain of
waypoints. Each waypoint cell depends on the previous one (dependency chain
models movement ordering). Units are placed at each waypoint.

**Scenario 3: Force Distribution** -- Distribute a total force across frontier
cells according to ratios. `ZoneRebalanceFaultPolicy` handles unit loss by
redistributing remaining forces to surviving cells.

## Node Types

| Type | NodeSpec | Purpose |
|------|---------|---------|
| `spatial:cell` | `CellSpec(row, col, height, terrainType)` | A grid cell -- foundation node |
| `spatial:unit` | `UnitSpec(cellId, strength)` | A military unit placed on a cell |
| `spatial:scout` | `ScoutSpec(cellId, visionRange)` | An observation unit that reveals fog of war |
| `spatial:zone` | `ZoneSpec(zoneName, allocation, totalForce)` | Aggregates cells with force allocation ratios |

## Terrain System

`TerrainGrid` -- 2D grid of `TerrainCell(row, col, height, terrainType)`.

`FogOfWar` -- Manhattan-distance vision with progressive reveal. Scouts reveal
cells within their vision range. Unrevealed cells are invisible to the planner.

| TerrainType | Meaning |
|------------|---------|
| OPEN | Flat terrain, no modifiers |
| FOREST | Concealment, reduced vision |
| MOUNTAIN | High ground, extended vision |
| WATER | Impassable |

## Zone Rebalance Fault Policy

When a zone detects NODE_DEGRADED (units lost), `ZoneRebalanceFaultPolicy`:

1. Identifies absent units in the zone's dependents
2. Removes them from the allocation map
3. Normalizes remaining ratios to sum to 1.0
4. Emits `UpdateNode` mutations for the zone spec and each surviving unit

This is a proportional rebalance -- the total force stays the same, but
remaining cells absorb the lost allocation. The runtime applies the mutations
via CAS, and the next reconciliation cycle provisions units at the new strengths.

## Key Insight

The graph model can represent spatial domains, but with trade-offs. Adjacency
maps to dependencies, zones map to aggregation nodes, and fog of war gates
what the compiler can "see." But true spatial operations (pathfinding, line of
sight, area effects) happen outside the graph -- the graph captures the WHAT
(desired deployment), while spatial logic lives in the compiler and fault policy.

The graph is not a spatial engine. It is a reconciliation target that happens
to describe spatial state.

## Running the Tests

```bash
mvn test -pl examples/spatial

# By scenario
mvn test -pl examples/spatial -Dtest=DefensePostureTest
mvn test -pl examples/spatial -Dtest=AttackWaypointsTest
mvn test -pl examples/spatial -Dtest=ForceDistributionTest
mvn test -pl examples/spatial -Dtest=SituationDetectionTest

# Terrain and world infrastructure
mvn test -pl examples/spatial -Dtest=TerrainGridTest
mvn test -pl examples/spatial -Dtest=FogOfWarTest
mvn test -pl examples/spatial -Dtest=BattlefieldWorldTest
mvn test -pl examples/spatial -Dtest=BattlefieldProvisionerTest
```

8 test classes covering terrain grid mechanics, fog of war reveal, defense
posture compilation, attack waypoint chains, force distribution with zone
rebalancing, situation detection, and battlefield provisioning.
