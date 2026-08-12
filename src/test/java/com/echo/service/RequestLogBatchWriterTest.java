package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogCheckpointRepository;
import com.echo.repository.RequestLogRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestLogBatchWriterTest {

    @Test
    void retentionPrunesToLowWatermarkInsteadOfDeletingEveryBatch() {
        RequestLogRepository logs = mock(RequestLogRepository.class);
        RequestLogCheckpointRepository checkpoints = mock(RequestLogCheckpointRepository.class);
        RequestLogBatchWriter writer = new RequestLogBatchWriter(logs, checkpoints);
        when(logs.count()).thenReturn(9_900L, 10_050L);
        when(logs.deleteOldest(1_050)).thenReturn(1_050);

        for (int batch = 1; batch <= 4; batch++) {
            writer.persist("spool", batch, batchOf(50), 10_000);
        }

        verify(logs, times(2)).count();
        verify(logs).deleteOldest(1_050);

        // 9000 -> 10000 uses twenty batches of headroom without another count/delete.
        for (int batch = 5; batch <= 24; batch++) {
            writer.persist("spool", batch, batchOf(50), 10_000);
        }
        verify(logs, times(2)).count();
        verify(logs, times(1)).deleteOldest(1_050);
    }

    @Test
    void freshCountPreventsStaleEstimateDeletingNewRowsAfterAdminCleanup() {
        RequestLogRepository logs = mock(RequestLogRepository.class);
        RequestLogCheckpointRepository checkpoints = mock(RequestLogCheckpointRepository.class);
        RequestLogBatchWriter writer = new RequestLogBatchWriter(logs, checkpoints);
        when(logs.count()).thenReturn(10_000L, 50L);

        writer.persist("spool", 1, batchOf(50), 10_000);
        writer.persist("spool", 2, batchOf(50), 10_000);

        verify(logs, times(2)).count();
        verify(logs, never()).deleteOldest(org.mockito.ArgumentMatchers.anyInt());
    }

    private List<RequestLog> batchOf(int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> RequestLog.builder()
                        .protocol(Protocol.HTTP)
                        .method("GET")
                        .endpoint("/batch/" + index)
                        .matched(true)
                        .responseTimeMs(1)
                        .requestTime(LocalDateTime.now())
                        .build())
                .toList();
    }
}
