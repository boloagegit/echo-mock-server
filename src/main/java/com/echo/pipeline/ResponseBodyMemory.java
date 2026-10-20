package com.echo.pipeline;

/** Optional transport-owned budget for decoded response storage, retained through queueing and delivery. */
@FunctionalInterface
public interface ResponseBodyMemory {
    ResponseBodyMemory UNBOUNDED = bytes -> true;

    /** Replace the previous estimate; a closed or exhausted transport rejects the new reservation. */
    boolean tryReserve(long bytes);
}
