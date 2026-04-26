package com.echo.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentRegistry 單元測試。
 * <p>
 * 測試空 agent 列表、多個 agent 註冊與查詢。
 * <p>
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4
 */
@ExtendWith(MockitoExtension.class)
class AgentRegistryTest {

    // ── 空 agent 列表 (Requirement 3.1, 3.2) ──

    @Test
    void getAll_withEmptyList_shouldReturnEmptyList() {
        AgentRegistry registry = new AgentRegistry(Collections.emptyList());

        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    void getByName_withEmptyList_shouldReturnEmpty() {
        AgentRegistry registry = new AgentRegistry(Collections.emptyList());

        assertThat(registry.getByName("any-agent")).isEmpty();
    }

    // ── 多個 agent 註冊與查詢 (Requirement 3.1, 3.2, 3.3, 3.4) ──

    @Test
    void getAll_withMultipleAgents_shouldReturnAllRegistered() {
        EchoAgent agent1 = mockAgent("log-agent");
        EchoAgent agent2 = mockAgent("metrics-agent");
        EchoAgent agent3 = mockAgent("audit-agent");

        AgentRegistry registry = new AgentRegistry(List.of(agent1, agent2, agent3));

        assertThat(registry.getAll()).hasSize(3);
        assertThat(registry.getAll()).containsExactly(agent1, agent2, agent3);
    }

    @Test
    void getByName_withValidName_shouldReturnCorrespondingAgent() {
        EchoAgent agent1 = mockAgent("log-agent");
        EchoAgent agent2 = mockAgent("metrics-agent");

        AgentRegistry registry = new AgentRegistry(List.of(agent1, agent2));

        Optional<EchoAgent> found = registry.getByName("log-agent");
        assertThat(found).isPresent();
        assertThat(found.get()).isSameAs(agent1);
    }

    @Test
    void getByName_withUnknownName_shouldReturnEmpty() {
        EchoAgent agent1 = mockAgent("log-agent");

        AgentRegistry registry = new AgentRegistry(List.of(agent1));

        assertThat(registry.getByName("non-existent")).isEmpty();
    }

    @Test
    void getByName_shouldDistinguishDifferentAgents() {
        EchoAgent agent1 = mockAgent("alpha");
        EchoAgent agent2 = mockAgent("beta");

        AgentRegistry registry = new AgentRegistry(List.of(agent1, agent2));

        assertThat(registry.getByName("alpha").get()).isSameAs(agent1);
        assertThat(registry.getByName("beta").get()).isSameAs(agent2);
    }

    @Test
    void getAll_withSingleAgent_shouldReturnSingletonList() {
        EchoAgent agent = mockAgent("solo-agent");

        AgentRegistry registry = new AgentRegistry(List.of(agent));

        assertThat(registry.getAll()).hasSize(1);
        assertThat(registry.getAll().get(0).getName()).isEqualTo("solo-agent");
    }

    // ── Helper ──

    private EchoAgent mockAgent(String name) {
        EchoAgent agent = mock(EchoAgent.class);
        when(agent.getName()).thenReturn(name);
        return agent;
    }
}
