package com.echo.service;

/**
 * A CharSequence that throws RuntimeException if regex matching exceeds a time limit.
 * Works because Java's regex engine calls charAt() repeatedly during backtracking.
 */
public class InterruptibleCharSequence implements CharSequence {
    private final CharSequence delegate;
    private final long deadline;

    public InterruptibleCharSequence(CharSequence delegate, long timeoutMs) {
        this.delegate = delegate;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public char charAt(int index) {
        if (System.currentTimeMillis() > deadline) {
            throw new RegexTimeoutException("Regex matching exceeded time limit");
        }
        return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new InterruptibleCharSequence(delegate.subSequence(start, end),
                deadline - System.currentTimeMillis());
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
