package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseRewriteCacheIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private Cache<Long, String> responseBodyCache;

    @ParameterizedTest
    @ValueSource(strings = {"after", ""})
    void rewritingAWarmSharedResponseUpdatesBothRulesBeforeSaveReturns(String replacement) {
        RuleDto rule = createHttpRule("/rewrite-cache/first", "GET", "before");
        RuleDto shared = RuleDto.builder().protocol(Protocol.HTTP)
                .matchKey("/rewrite-cache/shared").method("GET").status(200)
                .responseId(rule.getResponseId()).build();
        assertThat(adminClient().postForEntity("/api/admin/rules", shared, RuleDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(restTemplate.getForObject("/mock/rewrite-cache/first", String.class)).isEqualTo("before");
        assertThat(restTemplate.getForObject("/mock/rewrite-cache/shared", String.class)).isEqualTo("before");
        assertThat(responseBodyCache.getIfPresent(rule.getResponseId())).isEqualTo("before");

        var saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(Map.of("protocol", "HTTP", "matchKey", "/rewrite-cache/first",
                        "method", "GET", "status", 200, "version", rule.getVersion(),
                        "responseId", rule.getResponseId(), "responseBody", replacement,
                        "updateExistingResponse", true)), RuleDto.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).isNotNull();
        assertThat(saved.getBody().getResponseId()).isEqualTo(rule.getResponseId());
        for (String path : new String[]{"first", "shared"}) {
            var response = restTemplate.getForEntity("/mock/rewrite-cache/" + path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody() == null ? "" : response.getBody()).isEqualTo(replacement);
        }
    }
}
