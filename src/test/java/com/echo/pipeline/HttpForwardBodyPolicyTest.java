package com.echo.pipeline;

import com.echo.entity.Protocol;
import com.echo.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HttpForwardBodyPolicyTest {
    @Test
    void onlyCapableNativeRequestsWithBoundedListenersOptInBeforeDownstreamStarts() {
        var outbound = mock(HttpOutboundForwarder.class);
        var logging = mock(RequestLogService.class);
        var pipeline = new HttpMockPipeline(new ConditionMatcher(), mock(RuleService.class), logging,
                mock(HttpRuleService.class), new RestTemplate(), outbound);
        AtomicReference<MockRequest> observed = new AtomicReference<>();
        when(outbound.forwardDefaultAsync(any())).thenAnswer(call -> {
            observed.set(call.getArgument(0));
            return CompletableFuture.completedFuture(Optional.of(MockResponse.builder().status(200).body("ok").build()));
        });
        MockRequest nativeRequest = MockRequest.builder().protocol(Protocol.HTTP).method("GET")
                .path("/forward").acceptsUtf8ForwardBody(true).build();
        pipeline.execute(nativeRequest);
        assertThat(observed.get().isUseUtf8ForwardBody()).isTrue();
        assertThat(nativeRequest.isUseUtf8ForwardBody()).isFalse();
        pipeline.execute(nativeRequest.toBuilder().acceptsUtf8ForwardBody(false).build());
        assertThat(observed.get().isUseUtf8ForwardBody()).isFalse();
        ServeEventListener extension = event -> { };
        assertThat(extension.requiresFullResponseBody()).isTrue();
        pipeline.setServeEventListeners(List.of(extension));
        pipeline.execute(nativeRequest);
        assertThat(observed.get().isUseUtf8ForwardBody()).isFalse();
        pipeline.setServeEventListeners(List.of());
        pipeline.execute(nativeRequest);
        assertThat(observed.get().isUseUtf8ForwardBody()).isTrue();
    }
}
