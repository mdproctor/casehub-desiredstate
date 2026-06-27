package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpHumanNodeHandler implements HumanNodeHandler {
    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        return new StepOutcome.Skipped("requires human — no HumanNodeHandler configured");
    }
}
