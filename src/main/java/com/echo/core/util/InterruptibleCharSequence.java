package com.echo.core.util;

/**
 * A CharSequence that throws RuntimeException if regex matching exceeds a time limit.
 * Works because Java's regex engine calls charAt() repeatedly during backtracking.
 * <p>
 * The clock is read once every {@value #CHECK_INTERVAL} calls: reading it on every
 * call made ordinary matches on long inputs about 25x slower, while catastrophic
 * backtracking still performs millions of calls and is stopped promptly.
 * Instances are single-use and not thread-safe.
 */
public class InterruptibleCharSequence implements CharSequence {
    static final int CHECK_INTERVAL = 256;

    private final CharSequence delegate;
    private final long deadline;
    private int callsUntilCheck;

    public InterruptibleCharSequence(CharSequence delegate, long timeoutMs) {
        this(System.currentTimeMillis() + timeoutMs, delegate);
    }

    /** Shares an absolute deadline, e.g. with the sequence a subSequence was taken from. */
    private InterruptibleCharSequence(long deadline, CharSequence delegate) {
        this.delegate = delegate;
        this.deadline = deadline;
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public char charAt(int index) {
        if (--callsUntilCheck < 0) {
            callsUntilCheck = CHECK_INTERVAL;
            if (System.currentTimeMillis() > deadline) {
                throw new RegexTimeoutException("Regex matching exceeded time limit");
            }
        }
        return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new InterruptibleCharSequence(deadline, delegate.subSequence(start, end));
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    public static class RegexTimeoutException extends RuntimeException {
        public RegexTimeoutException(String message) {
            super(message);
        }
    }
}
