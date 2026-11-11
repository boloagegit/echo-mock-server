package com.echo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class RememberMeSigningKeyTest {
    @TempDir Path directory;

    @Test
    void explicitSecretDoesNotRequireFileAccess() throws Exception {
        Path file = directory.resolve("missing/key");
        assertThat(RememberMeSigningKey.resolve("deployment-secret", file)).isEqualTo("deployment-secret");
        assertThat(Files.exists(file.getParent())).isFalse();
    }

    @Test
    void defaultsProduceAnInstallationKeyAndReuseIt() throws Exception {
        Path file = directory.resolve("data/remember-me.key");
        String key = RememberMeSigningKey.resolve("change-me-in-production", file);
        assertThat(key).hasSize(43);
        assertThat(RememberMeSigningKey.resolve("echo-remember-me-secret", file)).isEqualTo(key);
        assertThat(RememberMeSigningKey.resolve("", file)).isEqualTo(key);
        assertThat(RememberMeSigningKey.resolve(null, file)).isEqualTo(key);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void pathsWithSpacesRemainUsable() throws Exception {
        Path file = directory.resolve("development workspace/data with spaces/remember-me.key");
        String key = RememberMeSigningKey.resolve("", file);
        assertThat(RememberMeSigningKey.resolve("", file)).isEqualTo(key);
    }

    @Test
    void differentInstallationsHaveDifferentKeys() throws Exception {
        assertThat(RememberMeSigningKey.resolve("", directory.resolve("one/key")))
                .isNotEqualTo(RememberMeSigningKey.resolve("", directory.resolve("two/key")));
    }

    @Test
    void concurrentInitializationDoesNotRotateTheKey() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<String>>();
            for (int i = 0; i < 32; i++) jobs.add(() -> RememberMeSigningKey.resolve("", directory.resolve("shared/key")));
            var results = pool.invokeAll(jobs);
            String first = results.get(0).get();
            for (var result : results) assertThat(result.get()).isEqualTo(first);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidKeyFilePathsFailWithAnIoError() {
        assertThatThrownBy(() -> RememberMeSigningKey.resolve("", null)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> RememberMeSigningKey.resolve("", directory.toAbsolutePath().getRoot()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void corruptOrOversizedStoredKeyFailsWithoutReplacingIt() throws Exception {
        Path file = directory.resolve("key");
        for (String value : new String[]{"invalid", "x".repeat(129)}) {
            Files.writeString(file, value);
            assertThatThrownBy(() -> RememberMeSigningKey.resolve("", file)).isInstanceOf(java.io.IOException.class);
            assertThat(Files.readString(file)).isEqualTo(value);
        }
    }

    @Test
    void heldFileLockFailsWithinAFiniteDeadline() throws Exception {
        Path file = directory.resolve("key");
        RememberMeSigningKey.resolve("", file);
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThat(held.isValid()).isTrue();
            assertTimeoutWithinLock(file);
        }
    }

    private static void assertTimeoutWithinLock(Path file) {
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(7), () ->
                assertThatThrownBy(() -> RememberMeSigningKey.resolve("", file))
                        .isInstanceOf(java.io.IOException.class).hasMessageContaining("Timed out"));
    }

    @Test
    void symlinkIsNotFollowed() throws Exception {
        Path target = directory.resolve("target");
        Files.writeString(target, "unchanged");
        Path link = directory.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable on this platform");
        }
        assertThatThrownBy(() -> RememberMeSigningKey.resolve("", link)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target)).isEqualTo("unchanged");
    }
}
