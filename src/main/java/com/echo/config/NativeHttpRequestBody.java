package com.echo.config;

import io.undertow.io.Receiver;

import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import org.xnio.channels.StreamSourceChannel;

/** Per-request bulk accumulator. The lease owns it until a worker takes the bytes. */
final class NativeHttpRequestBody implements AutoCloseable {
    private static final byte[] EMPTY = new byte[0];
    static final int INACTIVE = -2;
    private static final int READ_WINDOW = 64 * 1024;
    private final int maxBytes;
    private final int initialCapacity;
    private byte[] bytes = EMPTY;
    private int size;
    private boolean closed;
    private ByteBuffer readView;

    NativeHttpRequestBody(int maxBytes, long contentLength) {
        this.maxBytes = maxBytes;
        initialCapacity = (int) Math.max(0, Math.min(maxBytes, contentLength));
    }

    synchronized boolean append(byte[] chunk) throws Receiver.RequestToLargeException {
        if (closed) return false;
        ensureCapacity(chunk.length);
        System.arraycopy(chunk, 0, bytes, size, chunk.length);
        size += chunk.length;
        return true;
    }

    synchronized boolean append(ByteBuffer chunk) throws Receiver.RequestToLargeException {
        if (closed) return false;
        int length = chunk.remaining();
        ensureCapacity(length);
        chunk.get(bytes, size, length);
        size += length;
        return true;
    }

    /** Read only into owned storage; bound the JDK's temporary direct-buffer cache. */
    synchronized int read(StreamSourceChannel channel, ByteBuffer scratch,
                          BooleanSupplier active) throws IOException {
        if (closed) return INACTIVE;
        if (size == bytes.length) {
            // Probe before allocating/growing, and at the exact limit to distinguish EOF
            // from an oversized chunked upload without changing accepted bytes.
            scratch.clear();
            int count = channel.read(scratch);
            if (!active.getAsBoolean()) return INACTIVE;
            if (count > 0) {
                scratch.flip();
                append(scratch);
            }
            return count;
        }
        if (readView == null) readView = ByteBuffer.wrap(bytes);
        readView.limit(size + Math.min(READ_WINDOW, bytes.length - size)).position(size);
        int count = channel.read(readView);
        // The transport may fill unused array space before noticing expiry. Never
        // publish those bytes or advance the accepted length after the deadline.
        if (!active.getAsBoolean()) return INACTIVE;
        if (count > 0) size += count;
        return count;
    }

    private void ensureCapacity(int length) throws Receiver.RequestToLargeException {
        // Check before copying/allocating, including unknown-length chunked uploads.
        if (length > maxBytes - size) throw new Receiver.RequestToLargeException();
        int needed = size + length;
        if (needed > bytes.length) {
            int capacity = (int) Math.min(maxBytes,
                    Math.max(needed, Math.max(initialCapacity, 2L * bytes.length)));
            bytes = Arrays.copyOf(bytes, capacity);
            readView = null;
        }
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
        readView = null;
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
