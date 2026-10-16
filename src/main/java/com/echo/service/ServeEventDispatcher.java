package com.echo.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 依序把 {@link ServeEvent} 交給各 listener：primary（請求紀錄）與 critical listener 的例外往上傳，其他只記錄。
 * 不可變，可在多執行緒間共用。
 */
@Slf4j
public final class ServeEventDispatcher {

    private final ServeEventListener primary;
    private final List<ServeEventListener> others;

    private ServeEventDispatcher(ServeEventListener primary, List<ServeEventListener> others) {
        this.primary = primary;
        this.others = List.copyOf(others);
    }

    /**
     * 建立 dispatcher，primary 一定排在第一個。
     *
     * @param primary 一定會呼叫、例外一定往上傳的 listener（請求紀錄）；排在最前面，可為 null
     * @param others  其他 listener；與 primary 相同的實例會略過，避免重複紀錄
     */
    public static ServeEventDispatcher of(ServeEventListener primary, List<? extends ServeEventListener> others) {
        List<ServeEventListener> rest = new ArrayList<>();
        if (others != null) {
            for (ServeEventListener listener : others) {
                if (listener != null && listener != primary) {
                    rest.add(listener);
                }
            }
        }
        return new ServeEventDispatcher(primary, rest);
    }

    public void dispatch(ServeEvent event) {
        if (primary != null) {
            primary.onServed(event);
        }
        for (ServeEventListener listener : others) {
            if (listener.critical()) {
                listener.onServed(event);
                continue;
            }
            try {
                listener.onServed(event);
            } catch (RuntimeException error) {
                log.warn("ServeEventListener {} failed: {}", listener.getClass().getName(), error.getMessage());
            }
        }
    }

    int size() {
        return (primary != null ? 1 : 0) + others.size();
    }
}
