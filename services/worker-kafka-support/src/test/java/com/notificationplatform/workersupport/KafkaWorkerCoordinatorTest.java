package com.notificationplatform.workersupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KafkaWorkerCoordinatorTest {

    @Test
    void rejectedReservationsDoNotMoveTheExistingProviderSlot() {
        KafkaWorkerCoordinator coordinator = new KafkaWorkerCoordinator(
                "PUSH", null, new SimpleMeterRegistry(), 1, 5);

        for (int call = 0; call < 5; call++) {
            assertThat(coordinator.reserveProviderSlot()).isZero();
        }

        Duration firstWait = coordinator.reserveProviderSlot();
        Duration lastWait = firstWait;
        for (int attempt = 0; attempt < 100; attempt++) {
            lastWait = coordinator.reserveProviderSlot();
        }

        assertThat(firstWait).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(lastWait).isPositive().isLessThanOrEqualTo(firstWait);
    }
}
