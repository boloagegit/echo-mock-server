package com.echo.pipeline;

import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 協定無關的請求 Value Object
 * <p>
 * 封裝 HTTP 或 JMS 請求的共用資訊，僅在 pipeline 執行期間存在，不持久化。
 */
@Getter
@Builder
public class MockRequest {

    /** 協定類型 (HTTP / JMS) */
    private final Protocol protocol;

    /** HTTP method，JMS 為 null */
    private final String method;

    /** HTTP path 或 JMS queue name */
    private final String path;

    /** HTTP query string，JMS 為 null */
    private final String queryString;

    /** 請求 body */
    private final String body;

    /** 客戶端 IP，JMS 為 "JMS" */
    private final String clientIp;

    /** HTTP X-Original-Host 或 JMS target server */
    private final String targetHost;

    /** HTTP headers，JMS 為 null */
    private final Map<String, String> headers;

    /** JMS endpoint-field 值，HTTP 為 null */
    private final String endpointValue;

    /** 預解析的 body */
    private final ConditionMatcher.PreparedBody preparedBody;
}
