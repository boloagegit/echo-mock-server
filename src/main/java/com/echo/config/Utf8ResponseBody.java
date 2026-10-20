package com.echo.config;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded UTF-8 encoding. A returned buffer belongs to the sender until its send callback completes. */
final class Utf8ResponseBody {
    static final int BYTE_CAPACITY = 32 * 1024;
    static final int CHAR_CAPACITY = 16 * 1024;
    static final int BUFFER_BYTES = BYTE_CAPACITY + 2 * CHAR_CAPACITY;

    private final String value;
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer bytes = ByteBuffer.allocate(BYTE_CAPACITY);
    private final CharBuffer characters = CharBuffer.allocate(CHAR_CAPACITY);
    private int offset;
    private boolean finished;

    Utf8ResponseBody(String value) {
        this.value = value;
        characters.limit(0);
    }

    /** Same malformed-surrogate replacement as String.getBytes(UTF_8), checked before response headers. */
    static int encodedLength(String value, int maximum) {
        long length = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x80) length++;
            else if (character < 0x800) length += 2;
            else if (!Character.isSurrogate(character)) length += 3;
            else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else length++;
            if (length > maximum) return -1;
        }
        return (int) length;
    }

    boolean finished() { return finished; }

    ByteBuffer next() {
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
