package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.service.JmsTargetConnectionService;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Shared entry-point setup only; broker resources and assertions belong to each test. */
enum JmsForwardingRoute {
    LEGACY, DEFAULT, SELECTED;

    JmsTargetForwarder createForwarder(JmsProperties properties, List<JmsTargetFactoryProvider> providers) {
        return createForwarder(properties, providers, null);
    }

    JmsTargetForwarder createForwarder(JmsProperties properties, List<JmsTargetFactoryProvider> providers,
                                      JmsRuntimeMetrics metrics) {
        if (this == LEGACY) return new JmsTargetForwarder(properties, providers, metrics);

        // These fixtures only resolve targets; retaining every invocation would make
        // long-running recovery probes measure Mockito history instead of Echo.
        JmsTargetConnectionService service = mock(JmsTargetConnectionService.class, withSettings().stubOnly());
        // Resolve a fresh defensive snapshot so a probe's per-phase receive budget
        // applies to all routes, while its connection identity/revision stays fixed.
        Supplier<JmsTargetConnectionService.ResolvedTarget> resolved = () ->
                new JmsTargetConnectionService.ResolvedTarget("db:7:1", "Test", properties.getTarget(), false);
        if (this == DEFAULT) {
            when(service.resolveActive()).thenAnswer(invocation -> Optional.of(resolved.get()));
        } else {
            when(service.resolveEnabled("7")).thenAnswer(invocation -> resolved.get());
        }
        var forwarder = new JmsTargetForwarder(service, providers, metrics);
        forwarder.configureResources(properties);
        return forwarder;
    }

    String forward(JmsTargetForwarder forwarder, String body) {
        return this == SELECTED
                ? forwarder.forward(body, null, "7", false)
                : forwarder.forward(body, null);
    }

    String forward(JmsTargetForwarder forwarder, String body,
                   com.echo.diagnostics.TransactionDiagnostics.Trace trace) {
        return this == SELECTED
                ? forwarder.forwardWithMetadata(body, null, "7", false, trace).body()
                : forwarder.forwardWithMetadata(body, null, trace).body();
    }
}
