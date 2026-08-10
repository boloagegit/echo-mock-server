package com.echo.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Application-wide byte budget for HTTP response bodies currently being accumulated.
 * Reservations are incremental so small responses do not reserve the per-response maximum.
 */
final class ResponseBufferBudget {

    private final long maximumBytes;
    private final AtomicLong reservedBytes = new AtomicLong();

    ResponseBufferBudget(long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    Reservation openReservation() {
        return new Reservation(this);
    }

    long reservedBytes() {
        return reservedBytes.get();
    }

    long maximumBytes() {
        return maximumBytes;
    }

    private boolean tryReserve(long bytes) {
        if (bytes <= 0) return true;
        long current = reservedBytes.get();
        while (current <= maximumBytes - bytes) {
            if (reservedBytes.compareAndSet(current, current + bytes)) return true;
            current = reservedBytes.get();
        }
        return false;
    }

    private void release(long bytes) {
        if (bytes == 0) return;
        long remaining = reservedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            reservedBytes.addAndGet(bytes);
            throw new IllegalStateException("HTTP response buffer reservation released twice");
        }
    }

    static final class Reservation implements AutoCloseable {
        private final ResponseBufferBudget owner;
        private long bytes;
        private boolean closed;

        private Reservation(ResponseBufferBudget owner) {
            this.owner = owner;
        }

        synchronized boolean tryReserve(long additionalBytes) {
            if (closed) return false;
            if (!owner.tryReserve(additionalBytes)) return false;
            bytes += additionalBytes;
            return true;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.release(bytes);
            bytes = 0;
        }
    }
}
