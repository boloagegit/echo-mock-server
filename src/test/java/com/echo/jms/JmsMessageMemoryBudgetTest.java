package com.echo.jms;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmsMessageMemoryBudgetTest {

    @Test
    void reservesWeightedBytesAndReleasesExactlyOnce() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 2);

        var reservation = budget.reserveEncodedBody(20);
        assertThat(budget.reservedBytes()).isEqualTo(40);

        reservation.close();
        reservation.close();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void waitsForCapacityAndContinuesAfterRelease() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 1);
        var first = budget.reserveEncodedBody(80);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            started.countDown();
            try (var ignored = budget.reserveEncodedBody(30)) {
                acquired.countDown();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        waiter.start();

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(acquired.await(100, TimeUnit.MILLISECONDS)).isFalse();
        first.close();

        assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
        waiter.join(1000);
        assertThat(failure.get()).isNull();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void interruptingWaiterDoesNotLeakReservation() throws Exception {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 1);
        var first = budget.reserveEncodedBody(80);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            started.countDown();
            try (var ignored = budget.reserveEncodedBody(30)) {
                // capacity should never be acquired in this test
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        waiter.start();

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(1000);

        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(budget.reservedBytes()).isEqualTo(80);
        first.close();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void rejectsMessageThatCanNeverFitInsteadOfWaitingForever() {
        JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(100, 2);

        assertThatThrownBy(() -> budget.reserveEncodedBody(51))
                .isInstanceOf(JmsMessageMemoryBudget.JmsMessageTooLargeException.class)
                .hasMessageContaining("exceeding the processing budget");
    }

    @Test
    void derivesBudgetFromHeapPercentageWithSafeBounds() {
        assertThat(JmsMessageMemoryBudget.calculateMaximumBytes(512L * 1024 * 1024, 25))
                .isEqualTo(128L * 1024 * 1024);
        assertThatThrownBy(() -> JmsMessageMemoryBudget.calculateMaximumBytes(512, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
