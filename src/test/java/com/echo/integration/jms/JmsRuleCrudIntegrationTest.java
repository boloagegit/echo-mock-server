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
 * JMS rule CRUD integration test
 */
class JmsRuleCrudIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Creating a JMS rule returns 201 with protocol=JMS and matchKey=ORDER.Q")
    void createJmsRule_shouldReturn201WithCorrectFields() {
        RuleDto created = createJmsRule("ORDER.Q", "<ok/>");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(created.getMatchKey()).isEqualTo("ORDER.Q");
        assertThat(created.getResponseId()).isNotNull();
    }

    @Test
    @DisplayName("Reading a JMS rule returns 200 with correct fields, including responseBody")
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
    @DisplayName("Updating a JMS rule description returns 200 and a subsequent GET confirms the change")
    void updateJmsRule_shouldUpdateDescription() {
        RuleDto created = createJmsRule("ORDER.Q", "<ok/>");

        // GET first to obtain complete dto (including version)
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

        // Confirm again GET
        ResponseEntity<RuleDto> verifyResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(verifyResponse.getBody()).isNotNull();
        assertThat(verifyResponse.getBody().getDescription()).isEqualTo("已更新的 JMS 描述");
    }

    @Test
    @DisplayName("Deleting a JMS rule returns 204 and a subsequent GET returns 404")
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
