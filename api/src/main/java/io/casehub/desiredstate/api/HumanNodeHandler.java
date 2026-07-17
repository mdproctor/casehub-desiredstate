package io.casehub.desiredstate.api;

/**
 * Handles lifecycle actions for nodes marked with {@code requiresHuman = true}.
 * Called by SimpleTransitionExecutor for both provision and deprovision phases.
 */
public interface HumanNodeHandler {
    /**
     * Handle provisioning of a human-required node.
     * Returns Skipped if the node cannot be provisioned yet, or Succeeded/Failed based on outcome.
     */
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);

    /**
     * Handle deprovisioning of a human-required node.
     * Default returns Skipped — override in implementations that handle human-gated deprovision.
     */
    default StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
        return new StepOutcome.Skipped("deprovision not handled");
    }
}
