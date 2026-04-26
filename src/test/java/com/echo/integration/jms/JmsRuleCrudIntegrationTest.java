package com.echo.integration.jms;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JMS 規則 CRUD 整合測試
 */
class JmsRuleCrudIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("建立 JMS 規則 → 201，回傳 dto 包含 protocol=JMS、matchKey=ORDER.Q")
    void createJmsRule_shouldReturn201WithCorrectFields() {
        RuleDto created = createJmsRule("ORDER.Q", "<ok/>");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(created.getMatchKey()).isEqualTo("ORDER.Q");
        assertThat(created.getResponseId()).isNotNull();
    }

    @Test
    @DisplayName("讀取 JMS 規則 → 200，欄位正確（含 responseBody）")
    void getJmsRule_shouldReturn200WithCorrectFields() {
        RuleDto created = createJmsRule("ORDER.Q", "<ok/>");

        ResponseEntity<RuleDto> response = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto fetched = response.getBody();
        assertThat(fetched).isNotNull();
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(fetched.getMatchKey()).isEqualTo("ORDER.Q");
        assertThat(fetched.getResponseBody()).isEqualTo("<ok/>");
        assertThat(fetched.getResponseId()).isEqualTo(created.getResponseId());
    }

    @Test
    @DisplayName("更新 JMS 規則 description → 200，再 GET 確認已更新")
    void updateJmsRule_shouldUpdateDescription() {
        RuleDto created = createJmsRule("ORDER.Q", "<ok/>");

        // 先 GET 取得完整 dto（含 version）
        ResponseEntity<RuleDto> getResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        RuleDto toUpdate = getResponse.getBody();
        assertThat(toUpdate).isNotNull();

        toUpdate.setDescription("已更新的 JMS 描述");

        ResponseEntity<RuleDto> putResponse = adminClient().exchange(
                "/api/admin/rules/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(toUpdate),
                RuleDto.class);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 再 GET 確認
        ResponseEntity<RuleDto> verifyResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(verifyResponse.getBody()).isNotNull();
        assertThat(verifyResponse.getBody().getDescription()).isEqualTo("已更新的 JMS 描述");
    }

    @Test
    @DisplayName("刪除 JMS 規則 → 204，再 GET → 404")
    void deleteJmsRule_shouldReturn204ThenGetReturns404() {
        RuleDto created = createJmsRule("ORDER.Q", "<ok/>");

        ResponseEntity<Void> deleteResponse = adminClient().exchange(
                "/api/admin/rules/" + created.getId(),
                HttpMethod.DELETE,
                null,
                Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<RuleDto> getResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
