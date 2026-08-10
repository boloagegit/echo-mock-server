package com.echo.service;

import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking application-wide admission control for outbound HTTP requests.
 * It keeps one configurable total limit independent of Reactor Netty's
 * per-remote-host pools and provides an active deadline while waiting for capacity.
 */
final class ReactiveConnectionLimiter {

    private final AsyncPermitPool total;

    ReactiveConnectionLimiter(int maxTotal,
                              int maxPending,
                              ScheduledExecutorService scheduler) {
        this.total = new AsyncPermitPool(maxTotal, maxPending, scheduler);
    }

    Mono<Lease> acquire(Duration timeout) {
        return Mono.defer(() -> total.acquire(System.nanoTime() + timeout.toNanos())
                .map(Lease::new)
                .doOnDiscard(Lease.class, Lease::close));
    }

    int pendingCount() {
        return total.pendingCount();
    }

    record Lease(AsyncPermitPool.Slot total) implements AutoCloseable {
        @Override
        public void close() {
            total.close();
        }
    }

    private static final class AsyncPermitPool {
        private final int maximum;
        private final int maximumPending;
        private final AtomicInteger available;
        private final AtomicInteger pendingWaiters = new AtomicInteger();
        private final ConcurrentLinkedQueue<Waiter> waiters = new ConcurrentLinkedQueue<>();
        private final ScheduledExecutorService scheduler;

        private AsyncPermitPool(int maximum,
                                int maximumPending,
                                ScheduledExecutorService scheduler) {
            this.maximum = maximum;
            this.maximumPending = maximumPending;
            this.available = new AtomicInteger(maximum);
            this.scheduler = scheduler;
        }

        private Mono<Slot> acquire(long deadlineNanos) {
            return Mono.create(sink -> acquire(sink, deadlineNanos));
        }

        private void acquire(MonoSink<Slot> sink, long deadlineNanos) {
            if (tryAcquire()) {
                Slot slot = new Slot(this);
                sink.onCancel(slot::close);
                sink.success(slot);
                return;
            }

            if (!tryReservePending()) {
                sink.error(new CapacityException(
                        "HTTP_FORWARD_PENDING_CAPACITY_EXHAUSTED"));
                return;
            }
            Waiter waiter = new Waiter(this, sink);
            sink.onCancel(waiter::cancel);
            waiters.add(waiter);

            // A permit can be released between the first failed CAS and queue insertion.
            if (tryAcquire()) {
                if (waiters.remove(waiter)) {
                    if (!waiter.complete(new Slot(this))) release();
                } else {
                    release();
                }
                return;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                waiter.timeout();
                return;
            }
            ScheduledFuture<?> timeoutTask = scheduler.schedule(
                    waiter::timeout, remainingNanos, TimeUnit.NANOSECONDS);
            waiter.setTimeoutTask(timeoutTask);
        }

        private boolean tryAcquire() {
            int current = available.get();
            while (current > 0) {
                if (available.compareAndSet(current, current - 1)) return true;
                current = available.get();
            }
            return false;
        }

        private int pendingCount() {
            return pendingWaiters.get();
        }

        private boolean tryReservePending() {
            int current = pendingWaiters.get();
            while (current < maximumPending) {
                if (pendingWaiters.compareAndSet(current, current + 1)) return true;
                current = pendingWaiters.get();
            }
            return false;
        }

        private void release() {
            Waiter waiter;
            while ((waiter = waiters.poll()) != null) {
                if (waiter.complete(new Slot(this))) return;
            }
            int count = available.incrementAndGet();
            if (count > maximum) {
                available.decrementAndGet();
                throw new IllegalStateException("HTTP connection permit released twice");
            }
        }

        private static final class Slot implements AutoCloseable {
            private final AsyncPermitPool owner;
            private final AtomicBoolean closed = new AtomicBoolean();

            private Slot(AsyncPermitPool owner) {
                this.owner = owner;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) owner.release();
            }
        }

        private static final class Waiter {
            private final AsyncPermitPool owner;
            private final MonoSink<Slot> sink;
            private final AtomicBoolean pending = new AtomicBoolean(true);
            private volatile ScheduledFuture<?> timeoutTask;

            private Waiter(AsyncPermitPool owner, MonoSink<Slot> sink) {
                this.owner = owner;
                this.sink = sink;
            }

            private boolean complete(Slot permit) {
                if (!pending.compareAndSet(true, false)) return false;
                owner.pendingWaiters.decrementAndGet();
                cancelTimeout();
                sink.success(permit);
                return true;
            }

            private void timeout() {
                if (!pending.compareAndSet(true, false)) return;
                owner.waiters.remove(this);
                owner.pendingWaiters.decrementAndGet();
                sink.error(new ConnectionRequestTimeoutException(
                        "Timeout waiting for an HTTP connection"));
            }

            private void cancel() {
                if (!pending.compareAndSet(true, false)) return;
                owner.waiters.remove(this);
                owner.pendingWaiters.decrementAndGet();
                cancelTimeout();
            }

            private void setTimeoutTask(ScheduledFuture<?> task) {
                timeoutTask = task;
                if (!pending.get()) task.cancel(false);
            }

            private void cancelTimeout() {
                ScheduledFuture<?> task = timeoutTask;
                if (task != null) task.cancel(false);
            }
        }
    }

    static final class CapacityException extends RuntimeException {
        private CapacityException(String message) {
            super(message);
        }
    }
}
