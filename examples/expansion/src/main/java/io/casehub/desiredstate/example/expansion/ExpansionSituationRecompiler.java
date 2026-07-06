package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import io.casehub.ras.api.ActiveSituation;
import java.util.Optional;

public class ExpansionSituationRecompiler implements SituationRecompiler {

    private final ExpansionGoalCompiler compiler;
    private final ExpansionGoal originalGoal;

    public ExpansionSituationRecompiler(ExpansionGoalCompiler compiler, ExpansionGoal originalGoal) {
        this.compiler = compiler;
        this.originalGoal = originalGoal;
    }

    @Override
    public Optional<CompilationResult> recompile(
            DesiredStateGraph current, ActiveSituation situation,
            DesiredStateGraphFactory factory) {
        ExpansionGoal revised = originalGoal.withDefensePosture(DefensePosture.FORTIFY);
        return Optional.of(compiler.compile(revised, factory));
    }
}
