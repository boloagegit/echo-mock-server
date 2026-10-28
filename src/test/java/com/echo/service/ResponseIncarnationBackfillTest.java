package com.echo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

class ResponseIncarnationBackfillTest {
    @Test
    void waitsForReady() {
        var service = mock(ResponseService.class);
        var task = new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 32);
        task.tick();
        verifyNoInteractions(service);
    }

    @Test
    void stopsQueryingAfterCompletion() {
        var service = mock(ResponseService.class);
        when(service.incarnationBackfillBoundary()).thenReturn(10L);
        when(service.initializeMissingIncarnations(0, 10, 32))
                .thenReturn(new ResponseService.IncarnationBatch(0, 0, true));
        var task = new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 32);
        task.ready();
        task.tick();
        task.tick();
        task.tick();
        verify(service, times(1)).incarnationBackfillBoundary();
        verify(service, times(1)).initializeMissingIncarnations(0, 10, 32);
        verifyNoMoreInteractions(service);
    }

    @Test
    void advancesOnlyAfterSuccessfulBatch() {
        var service = mock(ResponseService.class);
        var clock = new AtomicLong(0);
        when(service.incarnationBackfillBoundary()).thenReturn(100L);
        when(service.initializeMissingIncarnations(0, 100, 32))
                .thenThrow(new IllegalStateException())
                .thenReturn(new ResponseService.IncarnationBatch(32, 32, false));
        when(service.initializeMissingIncarnations(32, 100, 32))
                .thenReturn(new ResponseService.IncarnationBatch(32, 0, true));
        var registry = new SimpleMeterRegistry();
        var task = new ResponseIncarnationBackfill(service, registry, 32, clock::get);
        task.ready();
        task.tick();
        task.tick();
        verify(service, times(1)).initializeMissingIncarnations(0, 100, 32);
        clock.set(5_000_000_000L);
        task.tick();
        task.tick();
        verify(service, times(2)).initializeMissingIncarnations(0, 100, 32);
        verify(service).initializeMissingIncarnations(32, 100, 32);
        assertEquals(
                1, registry.get("echo.response.incarnation.backfill.failures").counter().count());
        assertEquals(32, registry.get("echo.response.incarnation.backfill.rows").counter().count());
        assertEquals(
                1, registry.get("echo.response.incarnation.backfill.complete").gauge().value());
    }

    @Test
    void retriesFailedBoundaryReadWithoutInventingProgress() {
        var service = mock(ResponseService.class);
        var clock = new AtomicLong(3);
        when(service.incarnationBackfillBoundary())
                .thenThrow(new IllegalStateException())
                .thenReturn(0L);
        when(service.initializeMissingIncarnations(0, 0, 1))
                .thenReturn(new ResponseService.IncarnationBatch(0, 0, true));
        var task =
                new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 1, clock::get);
        task.ready();
        task.tick();
        task.tick();
        clock.set(5_000_000_003L);
        task.tick();
        verify(service, times(2)).incarnationBackfillBoundary();
        verify(service).initializeMissingIncarnations(0, 0, 1);
    }

    @Test
    void acceptsBatchOneAndRejectsUnboundedBatch() {
        var service = mock(ResponseService.class);
        assertDoesNotThrow(
                () -> new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 33));
    }

    @Test
    void keepsOneFiniteBoundaryAcrossBatches() {
        var service = mock(ResponseService.class);
        when(service.incarnationBackfillBoundary()).thenReturn(100L, 200L);
        when(service.initializeMissingIncarnations(0, 100, 32))
                .thenReturn(new ResponseService.IncarnationBatch(32, 0, false));
        when(service.initializeMissingIncarnations(32, 100, 32))
                .thenReturn(new ResponseService.IncarnationBatch(32, 0, true));
        var task = new ResponseIncarnationBackfill(service, new SimpleMeterRegistry(), 32);
        task.ready();
        task.tick();
        task.tick();
        verify(service, times(1)).incarnationBackfillBoundary();
        verify(service).initializeMissingIncarnations(32, 100, 32);
    }
}
