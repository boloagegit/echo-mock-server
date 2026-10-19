package com.echo.service;

/**
 * Receiving {@link ServeEvent}. Called synchronously on the thread handling the request, the implementation is non-blocking; the implementation requiring I/O queues itself.
 * <p>
 * The exception thrown by {@link #critical()} for listener (request record) of true will be uploaded: HTTP will return 503, and JMS will fail the delivery for resend;
 * Other listener exceptions will only be recorded and will not affect the response.
 */
public interface ServeEventListener {

    void onServed(ServeEvent event);

    default boolean critical() {
        return false;
    }
}
