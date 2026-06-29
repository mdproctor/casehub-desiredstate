package io.casehub.desiredstate.api;

import java.util.List;

public interface SituationSource {
    List<ActiveSituation> activeSituations(String tenancyId);
}
