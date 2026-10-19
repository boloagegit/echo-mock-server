package com.echo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/** Immutable identity of the running artifact, read once from packaged build metadata. */
@Service
@Slf4j
public class BuildInfoService {

    private final String version;
    private final Metadata metadata;

    public BuildInfoService(Optional<BuildProperties> buildProperties) {
        BuildProperties properties = buildProperties.orElse(null);
        version = properties != null && properties.getVersion() != null ? properties.getVersion() : "dev";
        String commit = properties != null ? properties.get("git.commit") : null;
        if (commit == null || !commit.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            commit = null;
        }
        String dirtyValue = properties != null ? properties.get("git.dirty") : null;
        Boolean dirty = (commit != null && ("true".equals(dirtyValue) || "false".equals(dirtyValue)))
                ? Boolean.valueOf(dirtyValue) : null;
        metadata = new Metadata(commit, commit != null ? commit.substring(0, 12) : null,
                dirty, properties != null ? properties.getTime() : null);
    }

    public Metadata getMetadata() {
        return metadata;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupVersion() {
        log.info("Running Echo version={}, commit={}, dirty={}, builtAt={}", version,
                metadata.commit() != null ? metadata.commit() : "unknown",
                metadata.dirty() != null ? metadata.dirty() : "unknown",
                metadata.time() != null ? metadata.time() : "unknown");
    }

    public record Metadata(String commit, String shortCommit, Boolean dirty, Instant time) { }
}
