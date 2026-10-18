package com.echo.config;

import io.undertow.io.Receiver;

import java.nio.charset.Charset;
import java.util.Arrays;

/** Per-request bulk accumulator. The lease owns it until a worker takes the bytes. */
final class NativeHttpRequestBody implements AutoCloseable {
    private static final byte[] EMPTY = new byte[0];
    private final int maxBytes;
    private final int initialCapacity;
    private byte[] bytes = EMPTY;
    private int size;
    private boolean closed;

    NativeHttpRequestBody(int maxBytes, long contentLength) {
        this.maxBytes = maxBytes;
        initialCapacity = (int) Math.max(0, Math.min(maxBytes, contentLength));
    }

    synchronized boolean append(byte[] chunk) throws Receiver.RequestToLargeException {
        if (closed) return false;
        // Check before copying/allocating, including unknown-length chunked uploads.
        if (chunk.length > maxBytes - size) throw new Receiver.RequestToLargeException();
        int needed = size + chunk.length;
        if (needed > bytes.length) {
            int capacity = (int) Math.min(maxBytes,
                    Math.max(needed, Math.max(initialCapacity, 2L * bytes.length)));
            bytes = Arrays.copyOf(bytes, capacity);
        }
        System.arraycopy(chunk, 0, bytes, size, chunk.length);
        size = needed;
        return true;
    }

    synchronized int size() { return size; }

    /** Detach once: decoding runs outside this lock and off the connection's I/O thread. */
    synchronized Bytes take() {
        if (closed) return null;
        var result = new Bytes(bytes, size);
        close();
        return result;
    }

    @Override
    public synchronized void close() {
        closed = true;
        bytes = EMPTY;
        size = 0;
    }

    static final class Bytes {
        private final byte[] bytes;
        private final int size;

        private Bytes(byte[] bytes, int size) {
            this.bytes = bytes;
            this.size = size;
        }

        String decode(Charset charset) {
            return size == 0 ? null : new String(bytes, 0, size, charset);
        }
    }
}
