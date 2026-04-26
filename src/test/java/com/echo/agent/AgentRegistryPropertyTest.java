package com.echo.agent;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentRegistry 屬性測試（Property 6）。
 * <p>
 * Feature: agent-framework-log-agent, Property 6: AgentRegistry lookup correctness
 * <p>
 * N 個 agent 註冊後 getAll 回傳 N 個；已註冊名稱可查到；未註冊名稱回傳 empty。
 * <p>
 * **Validates: Requirements 3.2, 3.3, 3.4**
 */
class AgentRegistryPropertyTest {

    // ==================== Stub EchoAgent ====================

    /**
     * 簡單的 EchoAgent stub，僅提供 getName() 回傳固定名稱。
     */
    static class StubAgent implements EchoAgent {

        private final String name;

        StubAgent(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "stub";
        }

        @Override
        public AgentStatus getStatus() {
            return AgentStatus.STOPPED;
        }

        @Override
        public AgentStats getStats() {
            return AgentStats.builder()
                    .queueSize(0)
                    .processedCount(0)
                    .droppedCount(0)
                    .build();
        }

        @Override
        public void submit(Object task) {
            // no-op
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }

    // ==================== Property 6 ====================

    /**
     * Feature: agent-framework-log-agent, Property 6: AgentRegistry lookup correctness
     * <p>
     * getAll() 回傳的 agent 數量等於註冊數量。
     * <p>
     * **Validates: Requirements 3.2**
     */
    @Property(tries = 20)
    void getAllReturnsExactlyNAgents(
            @ForAll @IntRange(min = 0, max = 20) int agentCount) {

        List<EchoAgent> agents = IntStream.range(0, agentCount)
                .mapToObj(i -> new StubAgent("agent-" + i))
                .collect(Collectors.toList());

        AgentRegistry registry = new AgentRegistry(agents);

        assertThat(registry.getAll()).hasSize(agentCount);
    }

    /**
     * Feature: agent-framework-log-agent, Property 6: AgentRegistry lookup correctness
     * <p>
     * 已註冊名稱可透過 getByName 查到對應 agent。
     * <p>
     * **Validates: Requirements 3.3**
     */
    @Property(tries = 20)
    void registeredAgentCanBeFoundByName(
            @ForAll @IntRange(min = 1, max = 20) int agentCount) {

        List<EchoAgent> agents = IntStream.range(0, agentCount)
                .mapToObj(i -> new StubAgent("agent-" + i))
                .collect(Collectors.toList());

        AgentRegistry registry = new AgentRegistry(agents);

        for (EchoAgent agent : agents) {
            Optional<EchoAgent> found = registry.getByName(agent.getName());
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo(agent.getName());
        }
    }

    /**
     * Feature: agent-framework-log-agent, Property 6: AgentRegistry lookup correctness
     * <p>
     * 未註冊名稱透過 getByName 回傳 empty。
     * <p>
     * **Validates: Requirements 3.4**
     */
    @Property(tries = 20)
    void unregisteredNameReturnsEmpty(
            @ForAll @IntRange(min = 0, max = 20) int agentCount,
            @ForAll @StringLength(min = 1, max = 30) String unknownSuffix) {

        List<EchoAgent> agents = IntStream.range(0, agentCount)
                .mapToObj(i -> new StubAgent("agent-" + i))
                .collect(Collectors.toList());

        Set<String> registeredNames = agents.stream()
                .map(EchoAgent::getName)
                .collect(Collectors.toSet());

        AgentRegistry registry = new AgentRegistry(agents);

        // Build a name guaranteed to not be in the registry
        String unknownName = "unknown-" + unknownSuffix;
        if (!registeredNames.contains(unknownName)) {
            assertThat(registry.getByName(unknownName)).isEmpty();
        }
    }

    /**
     * Feature: agent-framework-log-agent, Property 6: AgentRegistry lookup correctness
     * <p>
     * 綜合驗證：N 個 agent 註冊後 getAll 回傳 N 個；每個已註冊名稱可查到；
     * 未註冊名稱回傳 empty。
     * <p>
     * **Validates: Requirements 3.2, 3.3, 3.4**
     */
    @Property(tries = 20)
    void lookupCorrectnessComposite(
            @ForAll @IntRange(min = 0, max = 20) int agentCount) {

        List<EchoAgent> agents = IntStream.range(0, agentCount)
                .mapToObj(i -> new StubAgent("agent-" + i))
                .collect(Collectors.toList());

        AgentRegistry registry = new AgentRegistry(agents);

        // getAll returns exactly N
        assertThat(registry.getAll()).hasSize(agentCount);

        // Every registered name is found
        for (EchoAgent agent : agents) {
            assertThat(registry.getByName(agent.getName())).isPresent();
        }

        // A name that doesn't exist returns empty
        assertThat(registry.getByName("non-existent-agent-xyz")).isEmpty();
    }
}
