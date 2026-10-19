package com.echo.core.match;

/** Get the value of the condition to be judged from the request. The implementation must be thread-safe and stateless. */
@FunctionalInterface
public interface ValueExtractor {

    Extracted extract(RequestView request);
}
