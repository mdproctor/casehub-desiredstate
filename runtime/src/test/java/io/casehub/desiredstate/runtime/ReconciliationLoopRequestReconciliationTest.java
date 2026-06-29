package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationLoopRequestReconciliationTest {

    private ReconciliationLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    @Test
    void requestReconciliationTriggersReconcileForTenant() throws Exception {
        var readActualCount = new AtomicInteger(0);

        ActualStateAdapter adapter = (desired, tenancyId) -> {
            readActualCount.incrementAndGet();
            return new ActualState(java.util.Map.of());
        };

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource(),
            java.time.Duration.ofMillis(50),
            java.time.Duration.ofHours(1)  // long resync so only request triggers
        );

        var factory = new DefaultDesiredStateGraphFactory();
        loop.start("tenant-1", factory.empty());

        // Wait for initial reconciliation to settle
        Thread.sleep(200);
        int baseline = readActualCount.get();

        // Request reconciliation
        loop.requestReconciliation("tenant-1");

        // Wait for debounce window + execution time
        Thread.sleep(300);

        assertTrue(readActualCount.get() > baseline,
            "Expected reconciliation to be triggered. Baseline: " + baseline + ", current: " + readActualCount.get());
    }

    @Test
    void requestReconciliationForUnknownTenantIsNoOp() {
        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            (desired, tenancyId) -> new ActualState(java.util.Map.of()),
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource()
        );
        assertDoesNotThrow(() -> loop.requestReconciliation("nonexistent"));
    }
}
