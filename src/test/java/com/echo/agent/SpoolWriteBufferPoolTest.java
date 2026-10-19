package com.echo.agent;

import com.fasterxml.jackson.core.util.BufferRecycler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SpoolWriteBufferPoolTest {
    @Test
    void reusesTheLargeOutputBufferAndKeepsOnlyOneIdleRecycler() {
        var pool = new SpoolWriteBufferPool();
        var first = pool.acquireAndLinkPooled();
        byte[] buffer = first.allocByteBuffer(BufferRecycler.BYTE_WRITE_CONCAT_BUFFER);
        assertThat(buffer).hasSize(SpoolWriteBufferPool.CONCAT_BUFFER_BYTES);
        first.releaseByteBuffer(BufferRecycler.BYTE_WRITE_CONCAT_BUFFER, buffer);
        first.releaseToPool();

        var reused = pool.acquireAndLinkPooled();
        assertThat(reused.allocByteBuffer(BufferRecycler.BYTE_WRITE_CONCAT_BUFFER)).isSameAs(buffer);
        var leases = new ArrayList<BufferRecycler>();
        leases.add(reused);
        for (int i = 0; i < 7; i++) {
            leases.add(pool.acquireAndLinkPooled());
        }
        assertThat(leases).doesNotHaveDuplicates();
        leases.forEach(BufferRecycler::releaseToPool);
        assertThat(pool.pooledCount()).isEqualTo(1);
    }

    @Test
    void oversizedBuffersAreUsableButNotRetained() {
        var pool = new SpoolWriteBufferPool();
        var recycler = pool.acquireAndLinkPooled();
        byte[] bytes = recycler.allocByteBuffer(BufferRecycler.BYTE_WRITE_CONCAT_BUFFER,
                SpoolWriteBufferPool.CONCAT_BUFFER_BYTES * 2);
        byte[] other = recycler.allocByteBuffer(BufferRecycler.BYTE_WRITE_ENCODING_BUFFER,
                SpoolWriteBufferPool.MAX_OTHER_BYTE_BUFFER * 2);
        char[] chars = recycler.allocCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER,
                SpoolWriteBufferPool.MAX_CHAR_BUFFER * 2);
        recycler.releaseByteBuffer(BufferRecycler.BYTE_WRITE_CONCAT_BUFFER, bytes);
        recycler.releaseByteBuffer(BufferRecycler.BYTE_WRITE_ENCODING_BUFFER, other);
        recycler.releaseCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER, chars);
        recycler.releaseToPool();

        var next = pool.acquireAndLinkPooled();
        assertThat(next.allocByteBuffer(BufferRecycler.BYTE_WRITE_CONCAT_BUFFER))
                .hasSize(SpoolWriteBufferPool.CONCAT_BUFFER_BYTES).isNotSameAs(bytes);
        assertThat(next.allocByteBuffer(BufferRecycler.BYTE_WRITE_ENCODING_BUFFER)).isNotSameAs(other);
        assertThat(next.allocCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER)).isNotSameAs(chars);
        next.releaseToPool();
    }

    @Test
    void serializedPoolRestoresAnEmptyBoundedQueue() throws Exception {
        var pool = new SpoolWriteBufferPool();
        pool.acquireAndLinkPooled().releaseToPool();
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(pool);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (SpoolWriteBufferPool) input.readObject();
            assertThat(restored.pooledCount()).isZero();
            restored.acquireAndLinkPooled().releaseToPool();
            assertThat(restored.pooledCount()).isEqualTo(1);
        }
    }
}
