package com.echo.agent;

import com.fasterxml.jackson.core.util.BufferRecycler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SpoolReadBufferPoolTest {
    @Test
    void concurrentLeasesAreIndependentAndOnlyOneIdleRecyclerIsRetained() {
        var pool = new SpoolReadBufferPool();
        var leases = new ArrayList<BufferRecycler>();
        for (int i = 0; i < 8; i++) {
            leases.add(pool.acquireAndLinkPooled());
        }
        assertThat(leases).doesNotHaveDuplicates();
        leases.forEach(BufferRecycler::releaseToPool);
        assertThat(pool.pooledCount()).isEqualTo(1);
        assertThat(pool.clear()).isTrue();
        assertThat(pool.pooledCount()).isZero();
    }

    @Test
    void unusuallyLargeBuffersAreUsableButAreNotRetained() {
        var pool = new SpoolReadBufferPool();
        var recycler = pool.acquireAndLinkPooled();
        char[] largeText = recycler.allocCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER,
                SpoolReadBufferPool.TEXT_BUFFER_CHARACTERS * 2);
        byte[] largeBytes = recycler.allocByteBuffer(BufferRecycler.BYTE_READ_IO_BUFFER,
                SpoolReadBufferPool.MAX_RETAINED_BYTE_BUFFER * 2);
        recycler.releaseCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER, largeText);
        recycler.releaseByteBuffer(BufferRecycler.BYTE_READ_IO_BUFFER, largeBytes);
        recycler.releaseToPool();

        var next = pool.acquireAndLinkPooled();
        assertThat(next.allocCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER))
                .hasSize(SpoolReadBufferPool.TEXT_BUFFER_CHARACTERS).isNotSameAs(largeText);
        assertThat(next.allocByteBuffer(BufferRecycler.BYTE_READ_IO_BUFFER))
                .hasSizeLessThanOrEqualTo(SpoolReadBufferPool.MAX_RETAINED_BYTE_BUFFER)
                .isNotSameAs(largeBytes);
        next.releaseToPool();
    }

    @Test
    void serializedPoolRestoresAnEmptyUsableBoundedQueue() throws Exception {
        var original = new SpoolReadBufferPool();
        original.acquireAndLinkPooled().releaseToPool();
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (SpoolReadBufferPool) input.readObject();
            assertThat(restored.pooledCount()).isZero();
            restored.acquireAndLinkPooled().releaseToPool();
            assertThat(restored.pooledCount()).isEqualTo(1);
        }
    }
}
