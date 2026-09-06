package com.echo.protocol;

import com.echo.entity.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtocolHandlerRegistryOperatorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bulkUpdate_shouldStampAuthenticatedOperatorAndUpdateTime() {
        ProtocolHandler handler = mock(ProtocolHandler.class);
        when(handler.getProtocol()).thenReturn(Protocol.HTTP);
        when(handler.updateEnabled(eq(List.of("rule-1")), eq(false), any(LocalDateTime.class), eq("operator-a")))
                .thenReturn(1);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator-a", "n/a", List.of()));

        ProtocolHandlerRegistry registry = new ProtocolHandlerRegistry(List.of(handler));

        assertThat(registry.updateEnabled(List.of("rule-1"), false)).isEqualTo(1);
        verify(handler).updateEnabled(eq(List.of("rule-1")), eq(false),
                any(LocalDateTime.class), eq("operator-a"));
    }
}
