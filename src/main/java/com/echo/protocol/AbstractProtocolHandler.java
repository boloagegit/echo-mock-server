package com.echo.protocol;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Protocol Handler Abstract Base Class
 * <p>
 * Define batch operations for subclasses to implement by calling the repository's batch SQL directly.
 */
public abstract class AbstractProtocolHandler implements ProtocolHandler {

    @Override
    public abstract int updateEnabled(List<String> ids, boolean enabled, LocalDateTime updatedAt, String updatedBy);

    @Override
    public abstract int updateProtected(List<String> ids, boolean isProtected, LocalDateTime updatedAt, String updatedBy);

    @Override
    public abstract int extendRules(List<String> ids, LocalDateTime extendedAt, String updatedBy);
}
