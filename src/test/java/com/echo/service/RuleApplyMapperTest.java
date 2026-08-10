package com.echo.service;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleApplyMapperTest {

    private RuleApplyMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RuleApplyMapper(new ObjectMapper());
    }

    @Test
    void mapsStructuredJsonBodyAndObjectsToRuleDto() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RuleApplyDocument document = RuleApplyDocument.builder()
                .apiVersion(RuleApplyDocument.API_VERSION)
                .kind(RuleApplyDocument.KIND)
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey("/orders")
                        .method("POST")
                        .tags(Map.of("team", "payment"))
                        .responseHeaders(Map.of("Content-Type", "application/json"))
                        .responseBody(objectMapper.readTree("{\"ok\":true,\"count\":2}"))
                        .build())
                .build();

        RuleDto dto = mapper.toRuleDto(document);

        assertThat(objectMapper.readTree(dto.getResponseBody()))
                .isEqualTo(objectMapper.readTree("{\"ok\":true,\"count\":2}"));
        assertThat(objectMapper.readTree(dto.getTags()))
                .isEqualTo(objectMapper.readTree("{\"team\":\"payment\"}"));
        assertThat(objectMapper.readTree(dto.getResponseHeaders()))
                .isEqualTo(objectMapper.readTree("{\"Content-Type\":\"application/json\"}"));
    }

    @Test
    void preservesTextBodyWhenRoundTripping() {
        RuleDto dto = RuleDto.builder()
                .id("997a9b59-e57a-4777-9522-580cf38a5422")
                .version(4L)
                .protocol(Protocol.JMS)
                .matchKey("ORDER.Q")
                .responseBody("plain text\nwith a second line")
                .build();

        RuleApplyDocument document = mapper.fromRuleDto(dto);
        RuleDto mapped = mapper.toRuleDto(document);

        assertThat(document.getMetadata().getResourceVersion()).isEqualTo(4L);
        assertThat(document.getSpec().getResponseBody().isTextual()).isTrue();
        assertThat(mapped.getResponseBody()).isEqualTo("plain text\nwith a second line");
    }
}
