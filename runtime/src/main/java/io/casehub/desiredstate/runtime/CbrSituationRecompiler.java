package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.ras.api.ActiveSituation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CbrSituationRecompiler implements SituationRecompiler {

    private static final Logger LOG = Logger.getLogger(CbrSituationRecompiler.class.getName());

    private final ConfigurationRetriever retriever;
    private final ConfigurationAdapter adapter;
    private final PreferenceProvider preferenceProvider;

    public CbrSituationRecompiler(ConfigurationRetriever retriever,
                                   ConfigurationAdapter adapter,
                                   PreferenceProvider preferenceProvider) {
        this.retriever = retriever;
        this.adapter = adapter;
        this.preferenceProvider = preferenceProvider;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Optional<CompilationResult> recompile(
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        CbrConfiguration config = resolveConfiguration();

        RetrievalContext context = RetrievalContext.forSituation(current, actual, situation);

        List<RetrievedConfiguration> candidates = retriever.retrieve(context, config.maxCandidates());
        if (candidates.isEmpty()) {
            LOG.log(Level.INFO, "cbr.no-candidate: no similar configurations found for situation {0}",
                situation.situationId());
            return Optional.empty();
        }

        LOG.log(Level.INFO, "cbr.retrieve: {0} candidate(s), top confidence={1}",
            new Object[]{candidates.size(), candidates.stream()
                .mapToDouble(RetrievedConfiguration::confidence).max().orElse(0.0)});

        Optional<AdaptedConfiguration> best = candidates.stream()
            .filter(c -> c.confidence() >= config.minimumRetrievalConfidence())
            .map(c -> adapter.adapt(c, context))
            .flatMap(Optional::stream)
            .filter(a -> a.confidence() >= config.minimumAdaptationConfidence())
            .max(Comparator.comparingDouble(AdaptedConfiguration::confidence));

        if (best.isEmpty()) {
            LOG.log(Level.INFO, "cbr.no-candidate: no candidate survived confidence gates for situation {0}",
                situation.situationId());
            return Optional.empty();
        }

        AdaptedConfiguration selected = best.get();
        LOG.log(Level.INFO, "cbr.selected: sourceId={0}, confidence={1}, path=situation",
            new Object[]{selected.sourceId(), selected.confidence()});

        return Optional.of(CompilationResult.single(selected.graph()));
    }

    private CbrConfiguration resolveConfiguration() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
        double minRetrieval = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE).value();
        double minAdaptation = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_ADAPTATION_CONFIDENCE).value();
        int maxCandidates = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MAX_CANDIDATES).value();
        return new CbrConfiguration(minRetrieval, minAdaptation, maxCandidates);
    }
}
