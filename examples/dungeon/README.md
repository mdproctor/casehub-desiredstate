# Nefarious Dungeons

Teaching example that implements every core SPI to build and manage a dungeon
of rooms, creatures, and traps. The simplest complete desired-state domain --
start here to understand the full compile-plan-provision-reconcile cycle.

## What It Demonstrates

| Runtime Feature | How It's Used |
|----------------|---------------|
| `GoalCompiler` | `DungeonGoalCompiler` compiles a `DungeonBlueprint` (rooms, creatures, traps) into a graph |
| `NodeProvisioner` | `GoblinProvisioner` provisions rooms (BUILT), creatures (PRESENT), traps (ARMED) |
| `ActualStateAdapter` | `DungeonActualStateAdapter` maps world states to NodeStatus (DESTROYED -> ABSENT, DEGRADED -> DRIFTED) |
| `FaultPolicy` | `HeroRaidFaultPolicy` responds to NODE_DESTROYED by re-adding the destroyed node |
| `EventSource` | `DungeonEventSource` streams state changes for event-driven reconciliation |
| `HumanGating` | Dragon recruitment requires human approval (`humanCreature()` in blueprint builder) |
| Dependencies | Creatures depend on rooms; traps depend on rooms -- provisioning order enforced |
| SSE visualizer | `DungeonVisualizer` streams world snapshots via `/dungeon/stream` |

## Domain

A dungeon lord builds an underground lair. Rooms are the foundation -- creatures
and traps are placed inside rooms and depend on them. Heroes periodically raid
and destroy rooms. The fault policy rebuilds destroyed rooms automatically.

```
lair --------+
hatchery     |
library -----+---- dark-wizard (depends on library)
entrance ----+---- spike-trap  (depends on entrance)
```

## Node Types

| Type | NodeSpec | Provisioned State | Deprovisioned State |
|------|---------|------------------|-------------------|
| `room` | `DungeonRoomSpec(id, description, size)` | BUILT | room removed |
| `creature` | `CreatureSpec(species, level, humanGating)` | PRESENT | creature removed |
| `trap` | `TrapSpec(type, damage)` | ARMED | TRIGGERED |

## World States

`DungeonWorld` tracks each node's lifecycle state:

| State | Meaning | Maps to NodeStatus |
|-------|---------|-------------------|
| BUILT | Room constructed | PRESENT |
| PRESENT | Creature alive | PRESENT |
| ARMED | Trap ready | PRESENT |
| DESTROYED | Hero raid | ABSENT |
| FLED | Creature escaped | ABSENT |
| DEGRADED | Structural damage | DRIFTED |
| TRIGGERED | Trap sprung | ABSENT |

## Key Insight

Deprovisioning is domain-specific. When a trap is deprovisioned, it doesn't
disappear -- it triggers (ARMED -> TRIGGERED). When a creature is deprovisioned,
it's removed from the world. The same `deprovision()` SPI call produces
different domain effects based on node type.

## Running the Tests

```bash
mvn test -pl examples/dungeon

# Specific test
mvn test -pl examples/dungeon -Dtest=DungeonTest
```

8 tests: blueprint compilation, hero raid simulation, fault policy rebuild,
multi-dependency creatures, human-gated dragon, trap lifecycle,
actual state translation, fault type filtering.
