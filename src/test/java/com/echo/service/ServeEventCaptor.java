package com.echo.service;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** 測試用：取得 mock listener（通常是 mock 的 {@link RequestLogService}）收到的 {@link ServeEvent}。 */
public final class ServeEventCaptor {

    private ServeEventCaptor() {
    }

    /** 驗證剛好收到一個事件並回傳它。 */
    public static ServeEvent single(ServeEventListener listener) {
        ArgumentCaptor<ServeEvent> captor = ArgumentCaptor.forClass(ServeEvent.class);
        verify(listener).onServed(captor.capture());
        return captor.getValue();
    }

    /** 回傳收到的所有事件（至少一個）。 */
    public static List<ServeEvent> all(ServeEventListener listener) {
        ArgumentCaptor<ServeEvent> captor = ArgumentCaptor.forClass(ServeEvent.class);
        verify(listener, atLeastOnce()).onServed(captor.capture());
        return captor.getAllValues();
    }
}
