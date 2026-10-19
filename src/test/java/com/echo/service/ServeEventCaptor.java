package com.echo.service;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** For testing: get mock listener (usually {@link RequestLogService} of mock) received {@link ServeEvent}. */
public final class ServeEventCaptor {

    private ServeEventCaptor() {
    }

    /** Verify that exactly one event was received and post it back. */
    public static ServeEvent single(ServeEventListener listener) {
        ArgumentCaptor<ServeEvent> captor = ArgumentCaptor.forClass(ServeEvent.class);
        verify(listener).onServed(captor.capture());
        return captor.getValue();
    }

    /** Return all events received (at least one). */
    public static List<ServeEvent> all(ServeEventListener listener) {
        ArgumentCaptor<ServeEvent> captor = ArgumentCaptor.forClass(ServeEvent.class);
        verify(listener, atLeastOnce()).onServed(captor.capture());
        return captor.getAllValues();
    }
}
