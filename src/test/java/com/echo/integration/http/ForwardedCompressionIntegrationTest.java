package com.echo.integration.http;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.service.HttpTargetConnectionService;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibWrapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks content, not just HTTP status: raw deflate can silently lose its tail. */
@TestPropertySource(properties = "echo.http.transport=native")
class ForwardedCompressionIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired HttpTargetConnectionService targets;
    @Autowired HttpTargetConnectionRepository targetRepository;

    @Test
    void decoderKeepsEveryByteAcrossWrappersFragmentsAndBufferLimits() throws Exception {
        SoftAssertions checks = new SoftAssertions();
        for (ZlibWrapper wrapper : new ZlibWrapper[]{ZlibWrapper.NONE, ZlibWrapper.ZLIB, ZlibWrapper.GZIP}) {
            for (int length : new int[]{36, 33_333, 65_537}) {
                for (String unit : new String[]{"a", "銀行😀"}) {
                    byte[] expected = unit.repeat(length).getBytes(StandardCharsets.UTF_8);
                    byte[] compressed = compress(expected, wrapper);
                    for (int fragment : new int[]{1, 7, Integer.MAX_VALUE}) {
                        for (int maximum : new int[]{0, 1_048_576}) {
                            EmbeddedChannel decoder = new EmbeddedChannel(new JdkZlibDecoder(wrapper, maximum));
                            try {
                                for (int offset = 0; offset < compressed.length;) {
                                    int count = Math.min(fragment, compressed.length - offset);
                                    decoder.writeInbound(Unpooled.copiedBuffer(compressed, offset, count));
                                    offset += count;
                                }
                                decoder.finish();
                                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                                ByteBuf next;
                                while ((next = decoder.readInbound()) != null) {
                                    try {
                                        byte[] bytes = new byte[next.readableBytes()];
                                        next.readBytes(bytes);
                                        decoded.write(bytes);
                                    } finally { next.release(); }
                                }
                                checks.assertThat(decoded.toByteArray())
                                        .as("%s length=%s unit=%s fragment=%s maximum=%s", wrapper, length, unit, fragment, maximum)
                                        .isEqualTo(expected);
                            } finally { decoder.finishAndReleaseAll(); }
                        }
                    }
                }
            }
        }
        checks.assertAll();
    }

    @Test
    void smallDecompressionBuffersAcceptFittingOutputAndRejectExpansionBeyondTheLimit() throws Exception {
        for (ZlibWrapper wrapper : new ZlibWrapper[]{ZlibWrapper.NONE, ZlibWrapper.ZLIB, ZlibWrapper.GZIP}) {
            EmbeddedChannel decoder = new EmbeddedChannel(new JdkZlibDecoder(wrapper, 64));
            try {
                byte[] compressed = compress("a".repeat(36).getBytes(StandardCharsets.UTF_8), wrapper);
                for (byte value : compressed) decoder.writeInbound(Unpooled.wrappedBuffer(new byte[]{value}));
                decoder.finish();
                int decoded = 0;
                ByteBuf next;
                while ((next = decoder.readInbound()) != null) {
                    try { decoded += next.readableBytes(); }
                    finally { next.release(); }
                }
                assertThat(decoded).isEqualTo(36);
            } finally { decoder.finishAndReleaseAll(); }

            EmbeddedChannel bounded = new EmbeddedChannel(new JdkZlibDecoder(wrapper, 64));
            try {
                byte[] compressed = compress(new byte[4096], wrapper);
                assertThatThrownBy(() -> bounded.writeInbound(Unpooled.wrappedBuffer(compressed)))
                        .isInstanceOf(io.netty.handler.codec.DecoderException.class)
                        .hasMessageContaining("Decompression buffer has reached maximum size");
            } finally { bounded.finishAndReleaseAll(); }
        }
    }

    @Test
    void nativeForwardingKeepsStatusLengthAndHashAcrossCompressionAndFragments() throws Exception {
        HttpServer downstream = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        downstream.createContext("/", exchange -> {
            String[] parts = exchange.getRequestURI().getPath().substring(1).split("/");
            ZlibWrapper wrapper = ZlibWrapper.valueOf(parts[0]);
            int length = Integer.parseInt(parts[1]);
            int fragment = Integer.parseInt(parts[2]);
            byte[] compressed = compress("a".repeat(length).getBytes(StandardCharsets.UTF_8), wrapper);
            exchange.getResponseHeaders().set("Content-Encoding", wrapper == ZlibWrapper.GZIP ? "gzip" : "deflate");
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                for (int offset = 0; offset < compressed.length;) {
                    int count = Math.min(fragment, compressed.length - offset);
                    output.write(compressed, offset, count);
                    output.flush();
                    offset += count;
                }
            } finally { exchange.close(); }
        });
        downstream.start();
        try {
            targets.create(new HttpTargetConnectionRequest(null, "Compression regression",
                    "http://127.0.0.1:" + downstream.getAddress().getPort(), "NONE", null, null,
                    false, 2, 5, true, true, true));
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            SoftAssertions checks = new SoftAssertions();
            for (ZlibWrapper wrapper : new ZlibWrapper[]{ZlibWrapper.NONE, ZlibWrapper.ZLIB, ZlibWrapper.GZIP}) {
                for (int length : new int[]{36, 33_333, 65_537}) {
                    for (int fragment : new int[]{1, 7, Integer.MAX_VALUE}) {
                        String path = "/mock/" + wrapper + "/" + length + "/" + fragment;
                        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
                        checks.assertThat(response.statusCode()).as(path + " status").isEqualTo(200);
                        checks.assertThat(response.body().length).as(path + " length").isEqualTo(length);
                        checks.assertThat(MessageDigest.getInstance("SHA-256").digest(response.body()))
                                .as(path + " hash").isEqualTo(MessageDigest.getInstance("SHA-256")
                                        .digest("a".repeat(length).getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
            checks.assertAll();
        } finally {
            targetRepository.deleteAll();
            downstream.stop(0);
        }
    }

    private static byte[] compress(byte[] bytes, ZlibWrapper wrapper) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (wrapper == ZlibWrapper.GZIP) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        } else {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, wrapper == ZlibWrapper.NONE);
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(output, deflater)) {
                compressed.write(bytes);
            } finally { deflater.end(); }
        }
        return output.toByteArray();
    }
}
