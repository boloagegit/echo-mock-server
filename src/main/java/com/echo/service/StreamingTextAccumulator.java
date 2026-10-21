package com.echo.service;

import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.StringJoiner;

/** Decodes response chunks without retaining a second full byte-array copy of the body. */
final class StreamingTextAccumulator {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int MAX_DECODE_CHARS = 4 * 1024;
    private static final int MAX_COPY_BYTES = 8 * 1024;

    private final CharsetDecoder decoder;
    private StringJoiner text = new StringJoiner("");
    private byte[] remainder = EMPTY_BYTES;
    private CharBuffer decoded;
    private byte[] copyBuffer;
    private boolean finished;

    StreamingTextAccumulator(Charset charset) {
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    void append(byte[] chunk) {
        if (finished) throw new IllegalStateException("response body already finished");
        if (chunk == null || chunk.length == 0) return;
        append(chunk, 0, chunk.length);
    }

    /** Copies synchronously without retaining incoming buffers or changing their reader index. */
    void appendBuffer(ByteBuf chunk) {
        if (finished) {
            throw new IllegalStateException("response body already finished");
        }
        int remaining = chunk.readableBytes();
        if (remaining == 0) {
            return;
        }
        if (chunk.hasArray()) {
            append(chunk.array(), chunk.arrayOffset() + chunk.readerIndex(), remaining);
            return;
        }
        int required = Math.min(MAX_COPY_BYTES, remaining);
        if (copyBuffer == null || copyBuffer.length < required) {
            int capacity = copyBuffer == null ? required
                    : Math.max(required, Math.min(MAX_COPY_BYTES, copyBuffer.length * 2));
            copyBuffer = new byte[capacity];
        }
        int index = chunk.readerIndex();
        while (remaining > 0) {
            int length = Math.min(remaining, copyBuffer.length);
            chunk.getBytes(index, copyBuffer, 0, length);
            append(copyBuffer, 0, length);
            index += length;
            remaining -= length;
        }
    }

    private void append(byte[] chunk, int offset, int length) {
        ByteBuffer input;
        if (remainder.length == 0) {
            input = ByteBuffer.wrap(chunk, offset, length);
        } else {
            byte[] combined = new byte[remainder.length + length];
            System.arraycopy(remainder, 0, combined, 0, remainder.length);
            System.arraycopy(chunk, offset, combined, remainder.length, length);
            remainder = EMPTY_BYTES;
            input = ByteBuffer.wrap(combined);
        }
        decode(input, false);
        retainRemainder(input);
    }

    String finish() {
        if (finished) throw new IllegalStateException("response body already finished");
        finished = true;
        ByteBuffer input = ByteBuffer.wrap(remainder);
        remainder = EMPTY_BYTES;
        try {
            decode(input, true);
            flush();
            return text.toString();
        } finally {
            // Completion can synchronously enter listeners and the sender. They must
            // retain only the result, not the fragments used to construct it.
            text = null;
            decoded = null;
            copyBuffer = null;
        }
    }

    private void decode(ByteBuffer input, boolean endOfInput) {
        int capacity = Math.min(MAX_DECODE_CHARS, Math.max(32,
                (int) Math.ceil(Math.max(1, input.remaining()) * decoder.maxCharsPerByte())));
        CharBuffer output = decodeBuffer(capacity);
        while (true) {
            var result = decoder.decode(input, output, endOfInput);
            if (!result.isOverflow()) {
                return;
            }
            appendDecoded(output);
            output = decodeBuffer(Math.min(MAX_DECODE_CHARS, output.capacity() * 2));
        }
    }

    private void flush() {
        CharBuffer output = decodeBuffer(32);
        while (true) {
            var result = decoder.flush(output);
            appendDecoded(output);
            if (!result.isOverflow()) return;
        }
    }

    private void appendDecoded(CharBuffer output) {
        if (output.position() == 0) {
            return;
        }
        // Keep bounded text segments; StringJoiner allocates the final string once without growth copies.
        text.add(new String(output.array(), 0, output.position()));
        output.clear();
    }

    private CharBuffer decodeBuffer(int capacity) {
        // Per-response ownership avoids cross-request state and keeps scratch memory bounded.
        if (decoded == null) {
            decoded = CharBuffer.allocate(capacity);
        } else if (decoded.capacity() < capacity) {
            CharBuffer expanded = CharBuffer.allocate(capacity);
            decoded.flip();
            expanded.put(decoded);
            decoded = expanded;
        }
        return decoded;
    }

    private void retainRemainder(ByteBuffer input) {
        if (!input.hasRemaining()) return;
        remainder = new byte[input.remaining()];
        input.get(remainder);
    }
}
