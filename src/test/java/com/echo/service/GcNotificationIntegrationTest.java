package com.echo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GcNotificationIntegrationTest {
    @TempDir Path temporary;

    @Test
    void realCollectorNotificationsWorkInAnIsolatedSmallHeapJvm() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = Path.of(GcMonitoringService.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(GcNotificationProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output = temporary.resolve("gc notification probe.log");
        Process process = new ProcessBuilder(java, "-Xmx64m", "-XX:+UseSerialGC", "-cp", classpath,
                GcNotificationProbe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("isolated GC probe terminates").isTrue();
            String log = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(log).isZero();
            assertThat(log).contains("GC_PROBE_OK");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
