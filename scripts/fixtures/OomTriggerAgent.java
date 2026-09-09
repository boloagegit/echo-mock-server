package com.echo.resilience;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-only Java agent that exhausts a small, explicitly bounded test JVM heap. */
public final class OomTriggerAgent {

    private OomTriggerAgent() {
    }

    public static void premain(String triggerPath, Instrumentation instrumentation) {
        Thread worker = new Thread(() -> exhaustHeapAfterTrigger(Path.of(triggerPath)),
                "echo-test-oom-trigger");
        worker.setDaemon(true);
        worker.start();
    }

    private static void exhaustHeapAfterTrigger(Path trigger) {
        try {
            while (!Files.exists(trigger)) {
                Thread.sleep(25);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }

        List<byte[]> retained = new ArrayList<>();
        while (true) {
            retained.add(new byte[1024 * 1024]);
        }
    }
}
