package com.echo.jms;

import com.echo.config.JmsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JMS 訊息處理的全域記憶體預算。
 * <p>
 * 正常流量只需一次加減計數；超載時 listener 等待，讓尚未派送的訊息留在 Artemis queue。
 */
@Component
public final class JmsMessageMemoryBudget {

    private static final long MINIMUM_BUDGET_BYTES = 16L * 1024 * 1024;
    private final long maximumBytes;
    private final int expansionFactor;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicInteger waitingThreads = new AtomicInteger();
    private final ReentrantLock waitLock = new ReentrantLock(true);
    private final Condition capacityAvailable = waitLock.newCondition();

    @Autowired
    public JmsMessageMemoryBudget(JmsProperties properties) {
        this(calculateMaximumBytes(Runtime.getRuntime().maxMemory(),
                        properties.getProcessingMemoryPercent()),
                properties.getXmlMemoryExpansionFactor());
    }

    public JmsMessageMemoryBudget(long maximumBytes, int expansionFactor) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("JMS memory budget must be positive");
        }
        if (expansionFactor <= 0) {
            throw new IllegalArgumentException("JMS XML memory expansion factor must be positive");
        }
        this.maximumBytes = maximumBytes;
        this.expansionFactor = expansionFactor;
    }

    public Reservation reserveEncodedBody(long encodedBodyBytes) throws InterruptedException {
        if (encodedBodyBytes <= 0) {
            return Reservation.empty();
        }
        return reserve(estimateExpandedBytes(encodedBodyBytes));
    }

    public Reservation reserveText(String body) throws InterruptedException {
        if (body == null || body.isEmpty()) {
            return Reservation.empty();
        }
        long utf16Bytes = saturatingMultiply(body.length(), Character.BYTES);
        return reserve(estimateExpandedBytes(utf16Bytes));
    }

    private Reservation reserve(long estimatedBytes) throws InterruptedException {
        if (estimatedBytes > maximumBytes) {
            throw new JmsMessageTooLargeException(
                    "JMS message needs an estimated " + estimatedBytes
                            + " bytes, exceeding the processing budget of " + maximumBytes + " bytes");
        }

        // 已有訊息在等時不允許新訊息一直插隊，避免大訊息飢餓。
        if (waitingThreads.get() == 0 && tryReserve(estimatedBytes)) {
            return new Reservation(this, estimatedBytes);
        }

        waitLock.lockInterruptibly();
        try {
            waitingThreads.incrementAndGet();
            try {
                while (!tryReserve(estimatedBytes)) {
                    capacityAvailable.await();
                }
                return new Reservation(this, estimatedBytes);
            } finally {
                waitingThreads.decrementAndGet();
            }
        } finally {
            waitLock.unlock();
        }
    }

    private boolean tryReserve(long bytes) {
        long current = reservedBytes.get();
        while (current <= maximumBytes - bytes) {
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
            current = reservedBytes.get();
        }
        return false;
    }

    private long estimateExpandedBytes(long bodyBytes) {
        return saturatingMultiply(bodyBytes, expansionFactor);
    }

    private void release(long bytes) {
        if (bytes <= 0) {
            return;
        }
        long remaining = reservedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            reservedBytes.addAndGet(bytes);
            throw new IllegalStateException("JMS memory reservation released more than once");
        }
        if (waitingThreads.get() > 0) {
            waitLock.lock();
            try {
                capacityAvailable.signalAll();
            } finally {
                waitLock.unlock();
            }
        }
    }

    long reservedBytes() {
        return reservedBytes.get();
    }

    long maximumBytes() {
        return maximumBytes;
    }

    static long calculateMaximumBytes(long maxHeapBytes, int percent) {
        if (percent <= 0 || percent > 50) {
            throw new IllegalArgumentException("JMS processing memory percent must be between 1 and 50");
        }
        long calculated = maxHeapBytes / 100 * percent
                + maxHeapBytes % 100 * percent / 100;
        return Math.max(1, Math.min(maxHeapBytes, Math.max(MINIMUM_BUDGET_BYTES, calculated)));
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) {
            return 0;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    public static final class Reservation implements AutoCloseable {
        private final JmsMessageMemoryBudget owner;
        private final AtomicLong bytes;

        private Reservation(JmsMessageMemoryBudget owner, long bytes) {
            this.owner = owner;
            this.bytes = new AtomicLong(bytes);
        }

        static Reservation empty() {
            return new Reservation(null, 0);
        }

        @Override
        public void close() {
            if (owner == null) {
                return;
            }
            owner.release(bytes.getAndSet(0));
        }
    }

    static final class JmsMessageTooLargeException extends RuntimeException {
        JmsMessageTooLargeException(String message) {
            super(message);
        }
    }
}
