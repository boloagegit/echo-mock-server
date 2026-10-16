package com.echo.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHttpResourcesTest {
    @Test
    void cleanupFailureCannotLeakOtherReservationsOrCallbacksAndCloseIsIdempotent() {
        try (var resources = new NativeHttpResources(1, 2, 1024, 4096)) {
            var first = resources.acquire();
            var second = resources.acquire();
            assertThat(resources.acquire()).isNull();
            assertThat(first.reserve(3000)).isTrue();
            assertThat(second.reserve(2000)).isFalse();
            AtomicInteger cancelled = new AtomicInteger();
            first.onClose(() -> { throw new IllegalStateException("broken observer"); });
            first.onClose(cancelled::incrementAndGet);
            first.close();
            first.close();
            assertThat(cancelled).hasValue(1);
            assertThat(resources.active()).isEqualTo(1);
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(first.reserve(1)).isFalse();
            assertThat(second.reserve(4096)).isTrue();
            second.close();
            assertThat(resources.bufferedBytes()).isZero();
            assertThat(resources.active()).isZero();
        }
    }
}
