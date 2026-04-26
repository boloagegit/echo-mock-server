package com.echo.service;

import com.echo.entity.ResponseContentType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 回應內容驗證器註冊中心。
 * 以 ResponseContentType 為 key 管理所有驗證器，
 * 找不到對應驗證器時 fallback 到 TEXT 驗證器。
 */
@Component
public class ResponseContentValidatorRegistry {

    private final Map<ResponseContentType, ResponseContentValidator> validators;

    public ResponseContentValidatorRegistry(TextContentValidator textValidator,
                                            SseEventsContentValidator sseEventsValidator) {
        this.validators = new EnumMap<>(ResponseContentType.class);
        this.validators.put(ResponseContentType.TEXT, textValidator);
        this.validators.put(ResponseContentType.SSE_EVENTS, sseEventsValidator);
    }

    /**
     * 依 contentType 取得對應的驗證器。
     * 找不到時 fallback 到 TEXT 驗證器。
     *
     * @param contentType 回應內容類型
     * @return 對應的驗證器
     */
    public ResponseContentValidator getValidator(ResponseContentType contentType) {
        return validators.getOrDefault(contentType, validators.get(ResponseContentType.TEXT));
    }
}
