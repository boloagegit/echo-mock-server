package com.echo.controller;

import com.echo.dto.HttpForwardMetricsDto;
import com.echo.service.HttpOutboundForwarder;
import com.echo.service.HttpTargetConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpTargetConnectionControllerTest {

    @Test
    void waitingForAConnectionProbeDoesNotOccupyTheServletRequestThread() throws Exception {
        var service = mock(HttpTargetConnectionService.class);
        var forwarder = mock(HttpOutboundForwarder.class);
        var probe = new java.util.concurrent.CompletableFuture<HttpOutboundForwarder.ConnectionTestResult>();
        when(forwarder.testTimeoutMillis()).thenReturn(2_000L);
        when(forwarder.testAsync(1L)).thenReturn(probe);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new HttpTargetConnectionController(service, forwarder)).build();
        var pending = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/http-target-connections/1/test"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        assertThat(probe.isDone()).isFalse();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/admin/http-target-connections"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        probe.complete(new HttpOutboundForwarder.ConnectionTestResult(true, 123L, 200, null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
    }

    @Test
    void exposesForwardingAndPoolMetrics() {
        HttpTargetConnectionService service = mock(HttpTargetConnectionService.class);
        HttpOutboundForwarder forwarder = mock(HttpOutboundForwarder.class);
        var snapshot = new HttpForwardMetricsDto(
                2, 4, 10, 1, 0, 2,
                new HttpForwardMetricsDto.Pool(
                        2, 1, 3, 400, 1_000, 1_000, 10_485_760, 3_000, 30, 30),
                Map.of("profile:1", new HttpForwardMetricsDto.Target(
                        "Internal API", 1, 0, 1, 0, 0, 0, 0)));
        when(forwarder.metricsSnapshot()).thenReturn(snapshot);
        HttpTargetConnectionController controller =
                new HttpTargetConnectionController(service, forwarder);

        var response = controller.metrics();

        assertThat(response.getBody()).isSameAs(snapshot);
        var json = new ObjectMapper().valueToTree(snapshot);
        assertThat(json.get("ioThreads").asInt()).isEqualTo(4);
        assertThat(json.get("executorThreads").asInt()).isEqualTo(4);
        assertThat(json.get("completedForwards").asLong()).isEqualTo(10);
        assertThat(json.get("completedExecutorTasks").asLong()).isEqualTo(10);
        assertThat(json.get("pool").get("maxConnections").asInt()).isEqualTo(1_000);
        assertThat(json.get("pool").get("maxConnectionsPerPool").asInt()).isEqualTo(1_000);
        assertThat(json.get("pool").get("maxConnectionsPerRoute").asInt()).isEqualTo(1_000);
        assertThat(json.get("pool").get("maxPendingRequests").asInt()).isEqualTo(1_000);
        assertThat(json.get("pool").get("maxResponseBodyBytes").asInt()).isEqualTo(10_485_760);
        assertThat(json.get("pool").get("validateAfterInactivitySeconds").asInt()).isZero();
    }
}
