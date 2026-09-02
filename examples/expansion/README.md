# Expansion -- Build Then Defend

Lifecycle teaching example that demonstrates multi-phase desired-state management.
A colony expands to a new location: first build infrastructure (probe, nexus,
pylons, cannons), then transition to continuous defense (patrol, monitor, response).

Primary test vehicle for `CompilationResult.Lifecycle` and `LifecycleManager`.

## What It Demonstrates

| Runtime Feature | How It's Used |
|----------------|---------------|
| `CompilationResult.Lifecycle` | Two-phase compilation: "build" (terminates) then "defend" (runs forever) |
| `LifecycleManager` | Orchestrates phase transitions -- build completes when all nodes PRESENT, then swaps to defend graph |
| `CompletionCondition` | Build phase uses `allPresent()` (terminates); defend phase uses `never()` (steady-state) |
| `SituationRecompiler` | `ExpansionSituationRecompiler` escalates defense posture (PATROL -> FORTIFY) on persistent failures |
| `GoalCompiler` | `ExpansionGoalCompiler` produces a `Lifecycle` result with two distinct graphs |
| Carry-forward | Nexus appears in both phases -- defend phase continues reconciling build artifacts |
| Continuous reconciliation | Defend phase runs indefinitely; destroying a patrol node triggers automatic re-provisioning |

## Domain

A faction sends a probe to scout a location, then builds structures in dependency
order (probe -> nexus -> pylon -> cannon). Once construction completes, the system
transitions to a defense phase with patrol routes, monitoring, and response teams.

**Build phase graph:**
```
probe -> nexus -> pylon -> cannon
```

**Defend phase graph:**
```
        nexus (carry-forward)
       /     \
  patrol    monitor
       \     /
       response
```

## Node Types

| Type | NodeSpec | Phase | Purpose |
|------|---------|-------|---------|
| `probe` | `ProbeSpec(locationId)` | Build | Initial reconnaissance |
| `nexus` | `NexusSpec(locationId)` | Both | Central structure (carried forward) |
| `pylon` | `PylonSpec(locationId)` | Build | Power supply |
| `cannon` | `CannonSpec(locationId)` | Build | Static defense |
| `patrol` | `PatrolSpec(locationId)` | Defend | Perimeter patrol |
| `monitor` | `MonitorSpec(locationId)` | Defend | Surveillance |
| `response` | `ResponseSpec(locationId, posture)` | Defend | Reaction force |

## Defense Posture

`DefensePosture` enum controls response intensity:

| Posture | Meaning |
|---------|---------|
| PATROL | Standard patrol routes |
| FORTIFY | Hardened defense after threat detection |

`ExpansionSituationRecompiler` escalates from PATROL to FORTIFY when RAS
detects a persistent situation (e.g., repeated nexus failures). The recompiler
produces a new lifecycle with the updated posture, and `LifecycleManager`
swaps the desired graph.

## Key Insight

Lifecycle phases solve the "build then operate" pattern that appears in
infrastructure deployments, game expansions, and onboarding flows. The build
phase has a completion condition (all structures present), while the defend
phase runs forever. Carry-forward ensures build artifacts (nexus) remain
under reconciliation during the defend phase.

## Running the Tests

```bash
mvn test -pl examples/expansion

# Specific tests
mvn test -pl examples/expansion -Dtest=ExpansionLifecycleTest
mvn test -pl examples/expansion -Dtest=ExpansionDomainTest
```

5 lifecycle tests: build-to-defend transition, continuous defend reconciliation,
carry-forward of build artifacts, fault-triggered replanning, single-phase
backward compatibility.
