package com.echo.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/** Immutable, validated UTF-8 entity bytes owned by one native HTTP request. */
public final class Utf8ForwardBody {
    static final int SEGMENT_BYTES = 32 * 1024;
    private final List<byte[]> segments;
    private final int byteLength;
    private final int characterLength;
    private final char firstSignificantCharacter;

    /** Ownership of the arrays transfers here; the accumulator must never modify them again. */
    Utf8ForwardBody(List<byte[]> segments, int byteLength, int characterLength, char firstSignificantCharacter) {
        this.segments = List.copyOf(segments);
        this.byteLength = byteLength;
        this.characterLength = characterLength;
        this.firstSignificantCharacter = firstSignificantCharacter;
    }

    public int byteLength() { return byteLength; }
    public int characterLength() { return characterLength; }
    public char firstSignificantCharacter() { return firstSignificantCharacter; }
    public int segmentCount() { return segments.size(); }
    /** Payload capacities plus a conservative allowance for arrays and the immutable segment index. */
    public long retainedBytes() {
        return segments.isEmpty() ? 128 : (long) (segments.size() - 1) * SEGMENT_BYTES
                + segments.get(segments.size() - 1).length + 64L * segments.size() + 128;
    }

    /** Each send owns its buffer positions. No mutable array escapes this body. */
    public ByteBuffer[] batch(int first, int maximumSegments) {
        if (maximumSegments < 1) throw new IllegalArgumentException("Batch size must be positive");
        Objects.checkFromToIndex(first, first, segments.size());
        int count = Math.min(maximumSegments, segments.size() - first);
        ByteBuffer[] buffers = new ByteBuffer[count];
        for (int i = 0; i < count; i++) {
            int index = first + i;
            int length = Math.min(SEGMENT_BYTES, byteLength - index * SEGMENT_BYTES);
            buffers[i] = ByteBuffer.wrap(segments.get(index), 0, length).asReadOnlyBuffer();
        }
        return buffers;
    }

    /** Capture UTF-16 code units, including a possible split surrogate, just like String.substring. */
    public String prefix(int maximumCharacters) {
        if (maximumCharacters < 0) throw new IllegalArgumentException("Capture limit must not be negative");
        int remaining = Math.min(maximumCharacters, characterLength);
        if (remaining == 0) return "";
        StringJoiner result = new StringJoiner("");
        char[] characters = new char[Math.min(4096, remaining)];
        try (Reader reader = new InputStreamReader(new SegmentInputStream(), StandardCharsets.UTF_8)) {
            while (remaining > 0) {
                int count = reader.read(characters, 0, Math.min(remaining, characters.length));
                if (count < 0) throw new IllegalStateException("Validated UTF-8 body ended before its character count");
                result.add(new String(characters, 0, count));
                remaining -= count;
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return result.toString();
    }

    public String text() { return prefix(characterLength); }

    private final class SegmentInputStream extends InputStream {
        private int position;

        @Override
        public int read() {
            if (position == byteLength) return -1;
            int value = segments.get(position / SEGMENT_BYTES)[position % SEGMENT_BYTES] & 0xff;
            position++;
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            if (position == byteLength) return -1;
            int copied = Math.min(length, Math.min(byteLength - position, SEGMENT_BYTES - position % SEGMENT_BYTES));
            System.arraycopy(segments.get(position / SEGMENT_BYTES), position % SEGMENT_BYTES, target, offset, copied);
            position += copied;
            return copied;
        }
    }
}
