package com.echo.pipeline;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 協定無關的回應 Value Object
 * <p>
 * 封裝 pipeline 產生的回應資訊，僅在 pipeline 執行期間存在，不持久化。
 */
@Getter
@Builder
public class MockResponse {

    /** HTTP status code，JMS 固定 200 */
    private final int status;

    /** 回應 body */
    private final String body;

    /** HTTP response headers */
    private final Map<String, String> headers;

    /** 是否匹配成功 */
    private final boolean matched;

    /** 是否為轉發結果 */
    private final boolean forwarded;

    /** 轉發錯誤訊息 */
    private final String proxyError;
}
