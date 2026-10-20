package com.echo.service;

import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Validates bounded owned segments; malformed input switches immediately to the existing REPLACE decoder. */
final class Utf8ForwardAccumulator {
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    private CharBuffer characters;
    private final List<byte[]> segments = new ArrayList<>();
    private final byte[] remainder = new byte[4];
    private int remainderLength;
    private int received;
    private int characterCount;
    private char firstSignificant;
    private StreamingTextAccumulator replacement;
    private boolean finished;

    static long capacityFor(long bytes) {
        if (bytes == 0) return 0;
        if (bytes > Utf8ForwardBody.SEGMENT_BYTES) {
            return ((bytes + Utf8ForwardBody.SEGMENT_BYTES - 1) / Utf8ForwardBody.SEGMENT_BYTES)
                    * Utf8ForwardBody.SEGMENT_BYTES;
        }
        long full = (bytes - 1) / Utf8ForwardBody.SEGMENT_BYTES * Utf8ForwardBody.SEGMENT_BYTES;
        long tail = bytes - full;
        return full + Math.max(1, Long.highestOneBit(tail - 1) << 1);
    }

    void append(ByteBuf source) {
        if (finished) throw new IllegalStateException("Response body already finished");
        if (replacement != null) {
            received += source.readableBytes();
            replacement.appendBuffer(source);
            return;
        }
        int offset = source.readerIndex();
        int remaining = source.readableBytes();
        while (remaining > 0) {
            int segmentOffset = received % Utf8ForwardBody.SEGMENT_BYTES;
            int copied = Math.min(remaining, Utf8ForwardBody.SEGMENT_BYTES - segmentOffset);
            // Grow only the first segment for small responses. Once the body is large,
            // every further segment gets its final capacity before its first copy.
            int capacity = received >= Utf8ForwardBody.SEGMENT_BYTES ? Utf8ForwardBody.SEGMENT_BYTES
                    : (int) capacityFor((long) segmentOffset + copied);
            if (segmentOffset == 0) {
                segments.add(new byte[capacity]);
            } else if (segments.get(segments.size() - 1).length < capacity) {
                segments.set(segments.size() - 1, Arrays.copyOf(segments.get(segments.size() - 1), capacity));
            }
            byte[] segment = segments.get(segments.size() - 1);
            source.getBytes(offset, segment, segmentOffset, copied);
            received += copied;
            offset += copied;
            remaining -= copied;
            if (!validate(segment, segmentOffset, copied)) {
                switchToReplacement();
                if (remaining > 0) {
                    replacement.appendBuffer(source.slice(offset, remaining));
                    received += remaining;
                }
                return;
            }
        }
    }

    private boolean validate(byte[] segment, int offset, int length) {
        if (remainderLength > 0) {
            int copied = Math.min(length, 4 - remainderLength);
            System.arraycopy(segment, offset, remainder, remainderLength, copied);
            ByteBuffer pending = ByteBuffer.wrap(remainder, 0, remainderLength + copied);
            if (!decode(pending, false)) return false;
            int consumedNew = Math.max(0, pending.position() - remainderLength);
            if (pending.hasRemaining() && consumedNew == 0) {
                remainderLength = pending.remaining();
                return true;
            }
            remainderLength = 0;
            offset += consumedNew;
            length -= consumedNew;
        }
        ByteBuffer input = ByteBuffer.wrap(segment, offset, length);
        if (!decode(input, false)) return false;
        remainderLength = input.remaining();
        input.get(remainder, 0, remainderLength);
        return true;
    }

    private boolean decode(ByteBuffer input, boolean end) {
        int capacity = Math.min(4096, Math.max(32, input.remaining()));
        if (characters == null || characters.capacity() < capacity) characters = CharBuffer.allocate(capacity);
        while (true) {
            var result = decoder.decode(input, characters, end);
            characters.flip();
            characterCount += characters.remaining();
            if (firstSignificant == 0) {
                while (characters.hasRemaining()) {
                    char value = characters.get();
                    if (value > ' ') { firstSignificant = value; break; }
                }
            }
            characters.clear();
            if (result.isError()) return false;
            if (!result.isOverflow()) return true;
        }
    }

    private void switchToReplacement() {
        replacement = new StreamingTextAccumulator(StandardCharsets.UTF_8);
        int remaining = received;
        for (byte[] segment : segments) {
            int length = Math.min(remaining, segment.length);
            ByteBuf source = io.netty.buffer.Unpooled.wrappedBuffer(segment, 0, length);
            try { replacement.appendBuffer(source); }
            finally { source.release(); }
            remaining -= length;
        }
        segments.clear();
    }

    Result finish() {
        if (finished) throw new IllegalStateException("Response body already finished");
        finished = true;
        if (replacement == null && !decode(ByteBuffer.wrap(remainder, 0, remainderLength), true)) {
            switchToReplacement();
        }
        if (replacement != null) return new Result(replacement.finish(), null);
        Utf8ForwardBody body = new Utf8ForwardBody(segments, received, characterCount, firstSignificant);
        segments.clear();
        return new Result(null, body);
    }

    record Result(String text, Utf8ForwardBody bytes) {}
}
