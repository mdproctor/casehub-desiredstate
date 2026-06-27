package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoOpHumanNodeHandlerTest {

    record TestSpec(String value) implements NodeSpec {}

    @Test
    void returnsSkippedWithConfigurationMessage() {
        NoOpHumanNodeHandler handler = new NoOpHumanNodeHandler();
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v"), true
        );
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
            .of(List.of(node), List.of());
        ProvisionContext context = new ProvisionContext("tenant1", graph);

        StepOutcome outcome = handler.onProvision(node, context);

        assertInstanceOf(StepOutcome.Skipped.class, outcome);
        assertEquals("requires human — no HumanNodeHandler configured",
            ((StepOutcome.Skipped) outcome).reason());
    }
}
