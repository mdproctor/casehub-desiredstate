package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class WorkItemHumanNodeHandler implements HumanNodeHandler {

    private final WorkItemCreator workItemCreator;

    @Inject
    public WorkItemHumanNodeHandler(WorkItemCreator workItemCreator) {
        this.workItemCreator = workItemCreator;
    }

    @Override
    public StepOutcome onProvision(DesiredNode node, ProvisionContext context) {
        String callerRef = "desiredstate:" + context.tenancyId() + ":" + node.id().value() + ":provision";

        Optional<WorkItemRef> active = workItemCreator.findActiveByCallerRef(callerRef);
        if (active.isPresent()) {
            return new StepOutcome.Skipped(
                    "pending human action: WorkItem " + active.get().id());
        }

        WorkItemRef created = workItemCreator.create(WorkItemCreateRequest.builder()
                                                                          .title("Provision: " + node.id().value())
                                                                          .description("Human provisioning required for node "
                                                                                       + node.id().value() + " (type: " + node.type().value() + ")")
                                                                          .types(List.of("desiredstate-provision"))
                                                                          .callerRef(callerRef)
                                                                          .priority(WorkItemPriority.MEDIUM)
                                                                          .createdBy("desiredstate")
                                                                          .build());

        return new StepOutcome.Skipped(
                "pending human action: WorkItem " + created.id());
    }

    @Override
    public StepOutcome onDeprovision(DesiredNode node, DeprovisionContext context) {
        String callerRef = "desiredstate:" + context.tenancyId() + ":" + node.id().value() + ":deprovision";

        Optional<WorkItemRef> active = workItemCreator.findActiveByCallerRef(callerRef);
        if (active.isPresent()) {
            return new StepOutcome.Skipped(
                    "pending human action: WorkItem " + active.get().id());
        }

        WorkItemRef created = workItemCreator.create(WorkItemCreateRequest.builder()
                                                                          .title("Deprovision: " + node.id().value())
                                                                          .description("Human deprovision required for node "
                                                                                       + node.id().value() + " (type: " + node.type().value() + ")")
                                                                          .types(List.of("desiredstate-deprovision"))
                                                                          .callerRef(callerRef)
                                                                          .priority(WorkItemPriority.MEDIUM)
                                                                          .createdBy("desiredstate")
                                                                          .build());

        return new StepOutcome.Skipped(
                "pending human action: WorkItem " + created.id());
    }

}
