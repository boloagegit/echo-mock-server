package com.echo.agent;

import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.core.util.RecyclerPool;

import java.io.Serial;

/**
 * One idle recycler for spool decoding, independent of Jackson's thread-local pool.
 * A contiguous text buffer avoids repeated segments/copies for the default 64 KiB
 * logged bodies (including the truncation marker). This changes no JSON semantics.
 */
final class SpoolReadBufferPool extends RecyclerPool.BoundedPoolBase<BufferRecycler> {
    @Serial
    private static final long serialVersionUID = 1L;

    static final int TEXT_BUFFER_CHARACTERS = 128 * 1024;
    static final int MAX_RETAINED_BYTE_BUFFER = 16 * 1024;

    SpoolReadBufferPool() {
        super(1);
    }

    @Override
    public BufferRecycler createPooled() {
        return new ReadBufferRecycler();
    }

    @Serial
    private Object readResolve() {
        // BoundedPoolBase intentionally does not serialize its queue/capacity.
        return new SpoolReadBufferPool();
    }

    private static final class ReadBufferRecycler extends BufferRecycler {
        @Override
        protected int charBufferLength(int index) {
            return index == CHAR_TEXT_BUFFER ? TEXT_BUFFER_CHARACTERS : super.charBufferLength(index);
        }

        @Override
        public void releaseCharBuffer(int index, char[] buffer) {
            if (buffer.length <= TEXT_BUFFER_CHARACTERS) {
                super.releaseCharBuffer(index, buffer);
            }
        }

        @Override
        public void releaseByteBuffer(int index, byte[] buffer) {
            if (buffer.length <= MAX_RETAINED_BYTE_BUFFER) {
                super.releaseByteBuffer(index, buffer);
            }
        }
    }
}
