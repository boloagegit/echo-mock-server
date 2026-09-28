package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.service.JmsTargetConnectionService;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared entry-point setup only; broker resources and assertions belong to each test. */
enum JmsForwardingRoute {
    LEGACY, DEFAULT, SELECTED;

    JmsTargetForwarder createForwarder(JmsProperties properties, List<JmsTargetFactoryProvider> providers) {
        if (this == LEGACY) return new JmsTargetForwarder(properties, providers);

        JmsTargetConnectionService service = mock(JmsTargetConnectionService.class);
        var resolved = new JmsTargetConnectionService.ResolvedTarget("db:7:1", "Test", properties.getTarget(), false);
        if (this == DEFAULT) {
            when(service.resolveActive()).thenReturn(Optional.of(resolved));
        } else {
            when(service.resolveEnabled("7")).thenReturn(resolved);
        }
        return new JmsTargetForwarder(service, providers);
    }

    String forward(JmsTargetForwarder forwarder, String body) {
        return this == SELECTED
                ? forwarder.forward(body, null, "7", false)
                : forwarder.forward(body, null);
    }
}
