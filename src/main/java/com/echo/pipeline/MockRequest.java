package com.echo.pipeline;

import com.echo.entity.Protocol;
import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.service.ConditionMatcher;
import com.echo.service.ResponseTemplateService;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Protocol-independent request Value Object
 * <p>
 * The shared information encapsulated in HTTP or JMS requests only exists during the execution of pipeline and is not persistent.
 */
@Getter
@Builder
public class MockRequest {

    /** protocol type (HTTP / JMS) */
    private final Protocol protocol;

    /** HTTP method, JMS is null */
    private final String method;

    /** HTTP path or JMS queue name */
    private final String path;

    /** HTTP query string, JMS is null */
    private final String queryString;

    /** Request body */
    private final String body;

    /** Client IP, JMS is "JMS" */
    private final String clientIp;

    /** HTTP X-Original-Host or JMS target server */
    private final String targetHost;

    /** HTTP headers; JMS is the message header and attributes (JMSMessageID, JMSCorrelationID, custom attributes, etc.) */
    private final Map<String, String> headers;

    /** JMS endpoint-field value, HTTP is null */
    private final String endpointValue;

    /** Pre-parsed body */
    private final ConditionMatcher.PreparedBody preparedBody;

    /** Transient explicit context, not wire headers or persistence. */
    @Builder.Default
    private final Trace trace = Trace.NONE;

    /** Request data used by the response template; common to all protocols, see {@link ResponseTemplateService#renderIfTemplate}. */
    public ResponseTemplateService.TemplateContext templateContext() {
        return ResponseTemplateService.TemplateContext.of(path, method, queryString, headers, body);
    }
}
