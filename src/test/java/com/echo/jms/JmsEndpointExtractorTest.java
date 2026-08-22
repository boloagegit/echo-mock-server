package com.echo.jms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JmsEndpointExtractorTest {

    private final JmsEndpointExtractor extractor = new JmsEndpointExtractor();

    @Test
    void extractsNamespacedXmlElementWithoutBuildingDom() {
        String body = "<ns:root xmlns:ns='urn:test'><ns:ServiceName> OrderService </ns:ServiceName></ns:root>";

        assertThat(extractor.extract(body, "ServiceName")).isEqualTo("OrderService");
    }

    @Test
    void extractsTopLevelJsonFieldAndIgnoresNestedField() {
        assertThat(extractor.extract(
                "{\"nested\":{\"ServiceName\":\"Wrong\"},\"ServiceName\":\"PaymentService\"}",
                "ServiceName"))
                .isEqualTo("PaymentService");
    }

    @Test
    void rejectsDtdAndExternalEntities() {
        String body = "<!DOCTYPE root [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<root><ServiceName>&xxe;</ServiceName></root>";

        assertThat(extractor.extract(body, "ServiceName")).isNull();
    }

    @Test
    void returnsNullForInvalidOrOversizedEndpoint() {
        assertThat(extractor.extract("<invalid<<<", "ServiceName")).isNull();
        assertThat(extractor.extract(
                "<root><ServiceName>" + "x".repeat(4097) + "</ServiceName></root>",
                "ServiceName"))
                .isNull();
    }
}
