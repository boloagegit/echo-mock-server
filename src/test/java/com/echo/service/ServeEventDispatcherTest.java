package com.echo.service;

import com.echo.entity.Protocol;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class ServeEventDispatcherTest {

    private final ServeEvent event = ServeEvent.builder()
            .protocol(Protocol.HTTP).method("GET").endpoint("/a").matched(false).responseStatus(404).build();

    @Test
    void primaryRunsFirstAndIsNotDuplicated() {
        ServeEventListener primary = mock(ServeEventListener.class);
        ServeEventListener other = mock(ServeEventListener.class);

        ServeEventDispatcher dispatcher = ServeEventDispatcher.of(primary, List.of(other, primary));
        dispatcher.dispatch(event);

        InOrder order = inOrder(primary, other);
        order.verify(primary).onServed(same(event));
        order.verify(other).onServed(same(event));
        verify(primary, times(1)).onServed(any());
        assertThat(dispatcher.size()).isEqualTo(2);
    }

    @Test
    void primaryFailurePropagatesEvenIfNotMarkedCritical() {
        ServeEventListener primary = mock(ServeEventListener.class);
        ServeEventListener other = mock(ServeEventListener.class);
        doThrow(new RequestLogUnavailableException("down")).when(primary).onServed(any());

        assertThatThrownBy(() -> ServeEventDispatcher.of(primary, List.of(other)).dispatch(event))
                .isInstanceOf(RequestLogUnavailableException.class);
        verify(other, never()).onServed(any());
    }

    @Test
    void nonCriticalFailureIsIsolatedFromLaterListeners() {
        List<String> seen = new ArrayList<>();
        ServeEventListener failing = e -> {
            seen.add("failing");
            throw new IllegalStateException("boom");
        };
        ServeEventListener later = e -> seen.add("later");

        ServeEventDispatcher.of(null, List.of(failing, later)).dispatch(event);

        assertThat(seen).containsExactly("failing", "later");
    }

    @Test
    void criticalOtherListenerFailurePropagates() {
        ServeEventListener critical = new ServeEventListener() {
            @Override
            public void onServed(ServeEvent served) {
                throw new IllegalStateException("must surface");
            }

            @Override
            public boolean critical() {
                return true;
            }
        };

        assertThatThrownBy(() -> ServeEventDispatcher.of(null, List.of(critical)).dispatch(event))
                .isInstanceOf(IllegalStateException.class).hasMessage("must surface");
    }

    @Test
    void requestLogServiceIsACriticalListener() {
        RequestLogService service = mock(RequestLogService.class, CALLS_REAL_METHODS);
        assertThat(service.critical()).isTrue();
    }
}
