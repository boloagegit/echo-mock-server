package com.echo.config;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded UTF-8 encoding. A returned buffer belongs to the sender until its send callback completes. */
final class Utf8ResponseBody {
    static final int BYTE_CAPACITY = 32 * 1024;
    static final int CHAR_CAPACITY = 16 * 1024;
    private static final int BATCH_SIZE = 8;
    // Include preflight storage, the send batch and a small allowance for buffer metadata.
    static final int BUFFER_BYTES = (BATCH_SIZE + 1) * BYTE_CAPACITY + 2 * CHAR_CAPACITY + 4096;

    private final String value;
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer[] buffers = new ByteBuffer[BATCH_SIZE];
    private final ByteBuffer preflight = ByteBuffer.allocate(BYTE_CAPACITY);
    private ByteBuffer[] batch;
    private final CharBuffer characters = CharBuffer.allocate(CHAR_CAPACITY);
    private int offset;
    private boolean finished;

    Utf8ResponseBody(String value) {
        this.value = value;
        characters.limit(0);
    }

    /** Detect expansion with the bulk encoder; preserve the faster single-pass JDK path for non-ASCII text. */
    boolean needsSinglePass() {
        try {
            while (!finished) {
                int before = offset - characters.remaining();
                int bytes = encode(preflight).remaining();
                int consumed = offset - characters.remaining() - before;
                if (bytes != consumed) return true;
            }
            return false;
        } finally {
            encoder.reset();
            offset = 0;
            finished = false;
            characters.clear();
            characters.limit(0);
        }
    }

    boolean finished() { return finished; }

    ByteBuffer next() {
        return encode(preflight);
    }

    /** Gather a bounded group in one worker turn instead of dispatching for every 32 KiB. */
    void prepareBatch() {
        int count = 0;
        while (count < buffers.length && !finished) {
            if (buffers[count] == null) buffers[count] = ByteBuffer.allocate(BYTE_CAPACITY);
            encode(buffers[count++]);
        }
        batch = count == buffers.length ? buffers : Arrays.copyOf(buffers, count);
    }

    ByteBuffer[] batch() { return batch; }

    private ByteBuffer encode(ByteBuffer bytes) {
        bytes.clear();
        while (!finished) {
            characters.compact();
            int copied = Math.min(characters.remaining(), value.length() - offset);
            value.getChars(offset, offset + copied, characters.array(), characters.position());
            offset += copied;
            characters.position(characters.position() + copied);
            characters.flip();
            boolean end = offset == value.length();
            var result = encoder.encode(characters, bytes, end);
            if (result.isOverflow()) break;
            if (result.isError()) throw new IllegalStateException("UTF-8 replacement encoding failed");
            if (end) {
                var flushed = encoder.flush(bytes);
                finished = flushed.isUnderflow();
                break;
            }
        }
        bytes.flip();
        return bytes;
    }
}
