package com.echo.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** Persistent installation-specific key for deployments that have not supplied a secret. */
final class RememberMeSigningKey {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RememberMeSigningKey() { }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException busyInThisJvm) {
                // Another application context may be initializing the same installation.
            }
            if (System.nanoTime() - deadline >= 0) throw new IOException("Timed out locking remember-me key file");
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while locking remember-me key file", interrupted);
            }
        }
    }

    static synchronized String resolve(String configured, Path file) throws IOException {
        if (configured != null && !configured.isBlank()
                && !"change-me-in-production".equals(configured)
                && !"echo-remember-me-secret".equals(configured)) {
            return configured;
        }
        if (file == null) throw new IOException("Remember-me key file path is required");
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Remember-me key path must name a file");
        Files.createDirectories(parent);
        boolean posix = Files.getFileStore(parent).supportsFileAttributeView("posix");
        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        try (FileChannel channel = FileChannel.open(absolute,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS), attributes);
             var lock = acquireLock(channel)) {
            if (!lock.isValid()) throw new IOException("Remember-me key lock is not valid");
            if (posix) Files.setPosixFilePermissions(absolute, PosixFilePermissions.fromString("rw-------"));
            if (channel.size() == 0) {
                byte[] bytes = new byte[32];
                RANDOM.nextBytes(bytes);
                String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                ByteBuffer buffer = StandardCharsets.US_ASCII.encode(generated + "\n");
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
                return generated;
            }
            if (channel.size() > 128) throw new IOException("Invalid remember-me key file");
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            buffer.flip();
            String stored = StandardCharsets.US_ASCII.decode(buffer).toString().trim();
            try {
                if (stored.length() != 43 || Base64.getUrlDecoder().decode(stored).length != 32)
                    throw new IOException("Invalid remember-me key file");
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Invalid remember-me key file", malformed);
            }
            return stored;
        }
    }
}
