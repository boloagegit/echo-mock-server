package com.echo.agent;

import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.core.util.RecyclerPool;

import java.io.Serial;

/**
 * One idle recycler for large spool writes. Reusing the first output segment
 * avoids temporary segment arrays before Jackson copies the final JSON bytes.
 * The caller reserves memory first and falls back when this writer is busy.
 */
final class SpoolWriteBufferPool extends RecyclerPool.BoundedPoolBase<BufferRecycler> {
    @Serial
    private static final long serialVersionUID = 1L;

    static final int CONCAT_BUFFER_BYTES = 512 * 1024;
    static final long MIN_SERIALIZATION_RESERVATION = 2L * CONCAT_BUFFER_BYTES;
    static final int MAX_OTHER_BYTE_BUFFER = 16 * 1024;
    static final int MAX_CHAR_BUFFER = 8 * 1024;

    SpoolWriteBufferPool() {
        super(1);
    }

    @Override
    public BufferRecycler createPooled() {
        return new WriteBufferRecycler();
    }

    @Serial
    private Object readResolve() {
        return new SpoolWriteBufferPool();
    }

    private static final class WriteBufferRecycler extends BufferRecycler {
        @Override
        protected int byteBufferLength(int index) {
            return index == BYTE_WRITE_CONCAT_BUFFER ? CONCAT_BUFFER_BYTES : super.byteBufferLength(index);
        }

        @Override
        public void releaseByteBuffer(int index, byte[] buffer) {
            int limit = index == BYTE_WRITE_CONCAT_BUFFER ? CONCAT_BUFFER_BYTES : MAX_OTHER_BYTE_BUFFER;
            if (buffer.length <= limit) {
                super.releaseByteBuffer(index, buffer);
            }
        }

        @Override
        public void releaseCharBuffer(int index, char[] buffer) {
            if (buffer.length <= MAX_CHAR_BUFFER) {
                super.releaseCharBuffer(index, buffer);
            }
        }
    }
}
