package com.echo.pipeline;

/** Optional transport lifecycle; a finished response does not imply synchronous work has exited. */
public interface RequestWorkContext {
    RequestWorkContext NONE = new RequestWorkContext() {
        @Override public void checkActive() { }
        @Override public Scope begin() { return () -> { }; }
    };

    /** Stop before beginning another stage once cancellation or a deadline is observed. */
    void checkActive();

    /** Retain resource accounting until this actual use exits, including after cancellation. */
    Scope begin();

    interface Scope extends AutoCloseable {
        @Override void close();
    }
}
