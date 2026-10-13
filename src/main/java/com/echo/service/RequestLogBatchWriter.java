package com.echo.service;

import com.echo.entity.RequestLog;
import com.echo.entity.RequestLogCheckpoint;
import com.echo.repository.RequestLogCheckpointRepository;
import com.echo.repository.RequestLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Writes one durable request-log delivery batch and its checkpoint atomically. */
@Service
public class RequestLogBatchWriter {

    private static final int MAX_RETENTION_HEADROOM = 1_000;

    private final RequestLogRepository requestLogRepository;
    private final RequestLogCheckpointRepository checkpointRepository;
    private long estimatedRecordCount = -1;

    public RequestLogBatchWriter(RequestLogRepository requestLogRepository,
                                 RequestLogCheckpointRepository checkpointRepository) {
        this.requestLogRepository = requestLogRepository;
        this.checkpointRepository = checkpointRepository;
    }

    @Transactional(readOnly = true)
    public long findCheckpoint(String spoolId) {
        return checkpointRepository.findById(spoolId)
                .map(RequestLogCheckpoint::getLastSequence)
                .orElse(0L);
    }

    /**
     * The log rows and checkpoint share one transaction. Advancing the checkpoint
     * without the rows, or committing rows without the checkpoint, is impossible.
     */
    @Transactional
    public synchronized void persist(String spoolId, long lastSequence,
                                     List<RequestLog> logs, int maxRecords) {
        requestLogRepository.saveAll(logs);
        checkpointRepository.save(RequestLogCheckpoint.builder()
                .spoolId(spoolId)
                .lastSequence(lastSequence)
                .updatedAt(LocalDateTime.now())
                .build());

        enforceRetention(logs.size(), Math.max(1, maxRecords));
    }

    /**
     * Avoids count + oldest-ID lookup + delete on every small DB batch. Once the
     * maximum is crossed, prune to a low watermark and use the freed headroom for
     * subsequent batches. A fresh count is always taken before deleting, so admin
     * cleanup or delete-all cannot make the estimate remove new records by mistake.
     */
    private void enforceRetention(int inserted, int maxRecords) {
        if (estimatedRecordCount < 0) {
            // saveAll is flushed by this aggregate query, so the count includes this batch.
            estimatedRecordCount = requestLogRepository.count();
        } else {
            estimatedRecordCount += inserted;
        }
        if (estimatedRecordCount <= maxRecords) {
            return;
        }

        long actualCount = requestLogRepository.count();
        if (actualCount <= maxRecords) {
            estimatedRecordCount = actualCount;
            return;
        }

        int headroom = Math.max(inserted,
                Math.min(MAX_RETENTION_HEADROOM, Math.max(1, maxRecords / 10)));
        long targetCount = Math.max(0, (long) maxRecords - headroom);
        int requestedDelete = (int) Math.min(Integer.MAX_VALUE, actualCount - targetCount);
        int deleted = requestLogRepository.deleteOldest(requestedDelete);
        estimatedRecordCount = actualCount - deleted;
    }
}
