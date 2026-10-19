package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.service.RequestLogRejectedException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLogSpoolWriteBufferTest {
    @TempDir
    Path directory;
    private RequestLogSpool spool;

    @AfterEach
    void stop() {
        if (spool != null) spool.stop();
    }

    @Test
    void applicationJsonSettingsApplyToLargeAndSmallWritesAfterRestart() throws Exception {
        var mapper = mapperWith(new JsonSerializer<String>() {
            @Override
            public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(value.replace("SECRET", "MASKED"));
            }
        });
        mapper.getFactory().configure(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true);
        var originalPool = mapper.getFactory()._getRecyclerPool();
        Path path = directory.resolve("spool with spaces.sqlite");
        String body = "SECRET金融😀\n\"\\".repeat(7_000);
        spool = create(mapper, path);
        spool.start();
        spool.append(task(body));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT payload FROM request_log_spool")) {
            assertThat(rows.next()).isTrue();
            String json = new String(rows.getBytes(1), StandardCharsets.UTF_8);
            assertThat(json).contains("MASKED", "\\u91D1").doesNotContain("SECRET", "金融");
        }
        spool.stop();
        spool = create(mapper, path);
        spool.start();
        spool.append(task("SECRET small"));
        assertThat(spool.readAfter(0, 10)).extracting(entry -> entry.task().getRequestBody())
                .containsExactly(body.replace("SECRET", "MASKED"), "MASKED small");
        assertThat(mapper.getFactory()._getRecyclerPool()).isSameAs(originalPool);
    }

    @Test
    @Timeout(60)
    void concurrentLargeProducerCanCommitWhileTheSharedWriterIsBusy() throws Exception {
        String firstBody = "first".repeat(15_000);
        String secondBody = "second".repeat(15_000);
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var blockOnce = new AtomicBoolean(true);
        var mapper = mapperWith(new JsonSerializer<String>() {
            @Override
            public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                if (value.equals(firstBody) && blockOnce.compareAndSet(true, false)) {
                    firstEntered.countDown();
                    try {
                        if (!releaseFirst.await(30, TimeUnit.SECONDS)) throw new IOException("Fixture not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                }
                generator.writeString(value);
            }
        });
        spool = create(mapper, directory.resolve("concurrent.sqlite"));
        spool.start();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> spool.append(task(firstBody)));
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> spool.append(task(secondBody)));
            second.get(15, TimeUnit.SECONDS);
            releaseFirst.countDown();
            first.get(15, TimeUnit.SECONDS);
            assertThat(spool.readAfter(0, 10)).extracting(entry -> entry.task().getRequestBody())
                    .containsExactly(secondBody, firstBody);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void serializationFailureReleasesAdmissionMemoryAndAllowsTheNextWrite() {
        var failOnce = new AtomicBoolean(true);
        var mapper = mapperWith(new JsonSerializer<String>() {
            @Override
            public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                if (failOnce.compareAndSet(true, false)) throw new IOException("Synthetic serializer failure");
                generator.writeString(value);
            }
        });
        spool = create(mapper, directory.resolve("failure.sqlite"));
        spool.start();
        String body = "x".repeat(70_000);
        assertThatThrownBy(() -> spool.append(task(body))).isInstanceOf(RequestLogRejectedException.class);
        assertThat(spool.statusSnapshot().inFlightBytes()).isZero();
        spool.append(task(body));
        assertThat(spool.readAfter(0, 10)).singleElement()
                .satisfies(entry -> assertThat(entry.task().getRequestBody()).isEqualTo(body));
    }

    private RequestLogSpool create(ObjectMapper mapper, Path path) {
        // Strict producers also use the bounded writer; contention must never serialize callers.
        return new RequestLogSpool(mapper, path.toString(), 16, 16, 2, 10_000, 25,
                64L * 1024 * 1024, true);
    }

    private static ObjectMapper mapperWith(JsonSerializer<String> serializer) {
        var module = new SimpleModule();
        module.addSerializer(String.class, serializer);
        return new ObjectMapper().findAndRegisterModules().registerModule(module);
    }

    private static LogTask task(String body) {
        return LogTask.builder().protocol(Protocol.JMS).method("JMS").endpoint("BUFFER.TEST")
                .requestBody(body).responseBody(body + " reply").analysisBody(body).build();
    }
}
