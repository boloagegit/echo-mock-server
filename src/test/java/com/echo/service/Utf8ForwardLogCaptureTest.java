package com.echo.service;

import com.echo.agent.LogAgent;
import com.echo.agent.LogTask;
import com.echo.entity.Protocol;
import com.echo.repository.RequestLogRepository;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Utf8ForwardLogCaptureTest {
    @Test
    void durableTasksContainOnlyTheConfiguredTextSnapshotIncludingSplitSurrogates() {
        SystemConfigService config = mock(SystemConfigService.class);
        LogAgent agent = mock(LogAgent.class);
        @SuppressWarnings("unchecked") ObjectProvider<LogAgent> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agent);
        var service = new RequestLogService(mock(RequestLogRepository.class), config,
                mock(com.echo.protocol.ProtocolHandlerRegistry.class), provider);
        AtomicReference<LogTask> captured = new AtomicReference<>();
        doAnswer(call -> {
            Supplier<LogTask> supplier = call.getArgument(0);
            captured.set(supplier.get());
            return null;
        }).when(agent).submitDurably(any());
        String text = "a".repeat(65_535) + "😀銀行" + "z".repeat(70_000);
        Utf8ForwardAccumulator accumulator = new Utf8ForwardAccumulator();
        var source = Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.UTF_8));
        try { accumulator.append(source); }
        finally { source.release(); }
        var body = accumulator.finish().bytes();
        ServeEvent event = ServeEvent.builder().protocol(Protocol.HTTP).responseStatus(200)
                .utf8ResponseBody(body).requestBody("request").build();
        when(config.isRequestLogIncludeBody()).thenReturn(true);
        for (int maximum : new int[]{0, 1, 65_535, 65_536, 65_537, 65_538, text.length(), Integer.MAX_VALUE}) {
            when(config.getRequestLogMaxBodySize()).thenReturn(maximum);
            service.record(event);
            String expected = text.length() <= maximum ? text : text.substring(0, maximum) + "...(truncated)";
            assertThat(captured.get().getResponseBody()).as("limit=%s", maximum).isEqualTo(expected);
        }
        when(config.isRequestLogIncludeBody()).thenReturn(false);
        service.record(event);
        assertThat(captured.get().getResponseBody()).isNull();
        when(config.isRequestLogIncludeBody()).thenReturn(true);
        when(config.getRequestLogMaxBodySize()).thenReturn(31);
        service.record(event);
        assertThat(captured.get().getResponseBody()).isEqualTo(text.substring(0, 31) + "...(truncated)");
        clearInvocations(agent);
        when(config.isRequestLogDisabled()).thenReturn(true);
        service.record(event);
        verifyNoInteractions(agent);
        assertThat(service.requiresFullResponseBody()).isFalse();
    }
}
