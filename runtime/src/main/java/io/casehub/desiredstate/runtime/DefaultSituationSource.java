package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActiveSituation;
import io.casehub.desiredstate.api.SituationSource;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class DefaultSituationSource implements SituationSource {
    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return List.of();
    }
}
