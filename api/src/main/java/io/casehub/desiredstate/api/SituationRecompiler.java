package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;

import java.util.Optional;

/**
 * SPI for situation-driven graph recompilation.
 *
 * <p>Triggered by RAS (Runtime Anomaly Service) when a situation reaches a threshold
 * or pattern that requires desired-state recalculation — for example, persistent drift
 * in a specific zone triggering fallback provisioning, or cascading failures triggering
 * circuit-breaker topology changes.
 *
 * <p>Participates in {@code SituationRecompilerEngine} chain-of-responsibility.
 * Multiple recompilers may be registered; the engine tries each in {@link #priority()} order
 * (ascending) until one returns a non-empty result.
 *
 * @see io.casehub.ras.api.ActiveSituation
 * @see GoalCompiler
 */
public interface SituationRecompiler {

    Optional<CompilationResult> recompile(
            String tenancyId,
            DesiredStateGraph current,
            ActualState actual,
            ActiveSituation situation,
            DesiredStateGraphFactory factory);

    default int priority() { return 0; }
}
