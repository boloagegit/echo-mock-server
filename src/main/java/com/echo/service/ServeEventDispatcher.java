package com.echo.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Handle {@link ServeEvent} to each listener in sequence: primary (request record) and critical listener exceptions are uploaded, and others are only recorded.
 * Immutable and can be shared among multiple threads.
 */
@Slf4j
public final class ServeEventDispatcher {

    private final ServeEventListener primary;
    private final List<ServeEventListener> others;

    private ServeEventDispatcher(ServeEventListener primary, List<ServeEventListener> others) {
        this.primary = primary;
        this.others = List.copyOf(others);
    }

    /**
     * Create dispatcher, primary must be ranked first.
     *
     * @param primary listener (request record) will definitely be called and the exception will be uploaded; the first one can be null
     * @param others  Other listener; instances identical to primary will be skipped to avoid duplicate records
     */
    public static ServeEventDispatcher of(ServeEventListener primary, List<? extends ServeEventListener> others) {
        List<ServeEventListener> rest = new ArrayList<>();
        if (others != null) {
            for (ServeEventListener listener : others) {
                if (listener != null && listener != primary) {
                    rest.add(listener);
                }
            }
        }
        return new ServeEventDispatcher(primary, rest);
    }

    public void dispatch(ServeEvent event) {
        if (primary != null) {
            primary.onServed(event);
        }
        for (ServeEventListener listener : others) {
            if (listener.critical()) {
                listener.onServed(event);
                continue;
            }
            try {
                listener.onServed(event);
            } catch (RuntimeException error) {
                log.warn("ServeEventListener {} failed: {}", listener.getClass().getName(), error.getMessage());
            }
        }
    }

    public boolean requiresFullResponseBody() {
        if (primary != null && primary.requiresFullResponseBody()) return true;
        for (ServeEventListener listener : others) {
            if (listener.requiresFullResponseBody()) return true;
        }
        return false;
    }

    int size() {
        return (primary != null ? 1 : 0) + others.size();
    }
}
