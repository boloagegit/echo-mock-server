package com.echo.core.match;

/**
 * The matching condition is illegal. {@link #path()} indicates the location of the error, such as {@code $.all[1].op}.
 */
public class InvalidMatchSpecException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String path;

    public InvalidMatchSpecException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    public InvalidMatchSpecException(String path, String message, Throwable cause) {
        super(path + ": " + message, cause);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
