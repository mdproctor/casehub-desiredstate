package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SituationRecompilerEngine {

    private final List<SituationRecompiler> recompilers;

    public SituationRecompilerEngine(List<SituationRecompiler> recompilers) {
        this.recompilers = recompilers.stream()
            .sorted(Comparator.comparingInt(SituationRecompiler::priority))
            .toList();
    }

    public Optional<CompilationResult> recompile(
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.recompile(
                current, actual, situation, factory);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }
}
