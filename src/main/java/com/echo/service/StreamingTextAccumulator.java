package com.echo.service;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/** Decodes response chunks without retaining a second full byte-array copy of the body. */
final class StreamingTextAccumulator {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int MAX_DECODE_CHARACTERS = 8 * 1024;

    private final CharsetDecoder decoder;
    private final StringBuilder text = new StringBuilder(8 * 1024);
    private CharBuffer output;
    private byte[] remainder = EMPTY_BYTES;
    private boolean finished;

    StreamingTextAccumulator(Charset charset) {
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    void append(byte[] chunk) {
        if (finished) throw new IllegalStateException("response body already finished");
        if (chunk == null || chunk.length == 0) return;

        ByteBuffer input;
        if (remainder.length == 0) {
            input = ByteBuffer.wrap(chunk);
        } else {
            byte[] combined = new byte[remainder.length + chunk.length];
            System.arraycopy(remainder, 0, combined, 0, remainder.length);
            System.arraycopy(chunk, 0, combined, remainder.length, chunk.length);
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
        decode(input, true);
        flush();
        return text.toString();
    }

    private void decode(ByteBuffer input, boolean endOfInput) {
        prepareOutput(Math.min(MAX_DECODE_CHARACTERS, Math.max(32,
                (int) Math.ceil(Math.max(1, input.remaining()) * decoder.maxCharsPerByte()))));
        while (true) {
            var result = decoder.decode(input, output, endOfInput);
            appendDecoded();
            if (!result.isOverflow()) return;
        }
    }

    private void flush() {
        prepareOutput(32);
        while (true) {
            var result = decoder.flush(output);
            appendDecoded();
            if (!result.isOverflow()) return;
        }
    }

    private void prepareOutput(int capacity) {
        // Per response, bounded and grown only when needed. Small responses do not
        // pay for the maximum-sized buffer; large chunks reuse it on overflow.
        if (output == null || output.capacity() < capacity) {
            output = CharBuffer.allocate(capacity);
        }
    }

    private void appendDecoded() {
        // StringBuilder.append(CharBuffer) reads through CharSequence.charAt().
        // The array overload copies in bulk and preserves the decoder's output.
        text.append(output.array(), 0, output.position());
        output.clear();
    }

    private void retainRemainder(ByteBuffer input) {
        if (!input.hasRemaining()) return;
        remainder = new byte[input.remaining()];
        input.get(remainder);
    }
}
