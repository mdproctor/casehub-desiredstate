package io.casehub.desiredstate.api;

/**
 * Handles provisioning of nodes marked with {@code requiresHuman = true}.
 * Called by TransitionExecutor when encountering a human node during the provision phase.
 */
public interface HumanNodeHandler {
    /**
     * Handle provisioning of a human-required node.
     * Returns Skipped if the node cannot be provisioned yet, or Succeeded/Failed based on outcome.
     */
    StepOutcome onProvision(DesiredNode node, ProvisionContext context);
}
