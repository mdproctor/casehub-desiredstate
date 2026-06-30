package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.OrderedStep;
import io.serverlessworkflow.api.types.CallFunction;
import io.serverlessworkflow.api.types.CallTask;
import io.serverlessworkflow.api.types.Document;
import io.serverlessworkflow.api.types.FunctionArguments;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates Serverless Workflow definitions from ordered transition steps.
 * <p>
 * Each {@link OrderedStep} becomes a sequential {@code call} task in the workflow
 * that invokes the {@code desiredstate:dispatch} function with the node's id, type,
 * and action. The resulting Workflow can be handed to a casehub-engine Worker
 * for execution.
 */
@ApplicationScoped
public class TransitionWorkflowGenerator {

    static final String DSL_VERSION = "1.0.0";
    static final String DISPATCH_FUNCTION = "desiredstate:dispatch";

    /**
     * Generate a Serverless Workflow from ordered steps.
     *
     * @param steps       the steps to translate into workflow tasks
     * @param namespace   workflow namespace (e.g. "io.casehub.desiredstate")
     * @param name        workflow name (e.g. "prune-phase" or "grow-phase")
     * @param version     workflow version
     * @param executionId unique identifier for this execution context
     * @return a fully-formed Workflow ready for Worker construction
     */
    public Workflow generate(List<OrderedStep> steps, String namespace, String name, String version, String executionId) {
        Document document = new Document(DSL_VERSION, namespace, name, version);
        document.setTitle(name + " transition workflow");
        document.setSummary("Auto-generated desired-state transition workflow with " + steps.size() + " steps");

        List<TaskItem> taskItems = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            OrderedStep step = steps.get(i);
            taskItems.add(createTaskItem(step, i, executionId));
        }

        return new Workflow(document, taskItems);
    }

    private TaskItem createTaskItem(OrderedStep step, int index, String executionId) {
        String taskName = "step-" + index + "-" + step.action().name().toLowerCase()
            + "-" + step.node().id().value();

        FunctionArguments args = new FunctionArguments()
            .withAdditionalProperty("executionId", executionId)
            .withAdditionalProperty("nodeId", step.node().id().value())
            .withAdditionalProperty("nodeType", step.node().type().value())
            .withAdditionalProperty("action", step.action().name());

        CallFunction callFunction = new CallFunction()
            .withCall(DISPATCH_FUNCTION)
            .withWith(args);

        Task task = new Task().withCallTask(
            new CallTask().withCallFunction(callFunction)
        );

        return new TaskItem(taskName, task);
    }
}
