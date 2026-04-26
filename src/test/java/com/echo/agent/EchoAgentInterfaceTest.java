package com.echo.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 單元測試：驗證 EchoAgent 介面定義、AgentStatus 列舉值完整性、AgentStats builder 正確建構。
 * Validates: Requirements 1.1, 1.2, 1.3
 */
class EchoAgentInterfaceTest {

    // ── AgentStatus 列舉值完整性 (Requirement 1.2) ──

    @Test
    void agentStatus_shouldHaveExactlyFourValues() {
        assertThat(AgentStatus.values()).hasSize(4);
    }

    @Test
    void agentStatus_shouldContainAllExpectedValues() {
        assertThat(AgentStatus.values()).containsExactly(
                AgentStatus.STARTING,
                AgentStatus.RUNNING,
                AgentStatus.STOPPING,
                AgentStatus.STOPPED
        );
    }

    @Test
    void agentStatus_valueOf_shouldResolveCorrectly() {
        assertThat(AgentStatus.valueOf("STARTING")).isEqualTo(AgentStatus.STARTING);
        assertThat(AgentStatus.valueOf("RUNNING")).isEqualTo(AgentStatus.RUNNING);
        assertThat(AgentStatus.valueOf("STOPPING")).isEqualTo(AgentStatus.STOPPING);
        assertThat(AgentStatus.valueOf("STOPPED")).isEqualTo(AgentStatus.STOPPED);
    }

    // ── AgentStats builder 正確建構 (Requirement 1.3) ──

    @Test
    void agentStats_builder_shouldSetAllFields() {
        AgentStats stats = AgentStats.builder()
                .queueSize(10)
                .processedCount(100L)
                .droppedCount(5L)
                .build();

        assertThat(stats.getQueueSize()).isEqualTo(10);
        assertThat(stats.getProcessedCount()).isEqualTo(100L);
        assertThat(stats.getDroppedCount()).isEqualTo(5L);
    }

    @Test
    void agentStats_builder_shouldDefaultToZeroValues() {
        AgentStats stats = AgentStats.builder().build();

        assertThat(stats.getQueueSize()).isZero();
        assertThat(stats.getProcessedCount()).isZero();
        assertThat(stats.getDroppedCount()).isZero();
    }

    @Test
    void agentStats_builder_shouldHandleLargeValues() {
        AgentStats stats = AgentStats.builder()
                .queueSize(Integer.MAX_VALUE)
                .processedCount(Long.MAX_VALUE)
                .droppedCount(Long.MAX_VALUE)
                .build();

        assertThat(stats.getQueueSize()).isEqualTo(Integer.MAX_VALUE);
        assertThat(stats.getProcessedCount()).isEqualTo(Long.MAX_VALUE);
        assertThat(stats.getDroppedCount()).isEqualTo(Long.MAX_VALUE);
    }

    // ── EchoAgent 介面方法定義 (Requirement 1.1) ──

    @Test
    void echoAgent_shouldDeclareAllRequiredMethods() throws NoSuchMethodException {
        Class<EchoAgent> iface = EchoAgent.class;

        assertThat(iface.isInterface()).isTrue();
        assertThat(iface.getMethod("getName").getReturnType()).isEqualTo(String.class);
        assertThat(iface.getMethod("getStatus").getReturnType()).isEqualTo(AgentStatus.class);
        assertThat(iface.getMethod("getStats").getReturnType()).isEqualTo(AgentStats.class);
        assertThat(iface.getMethod("submit", Object.class).getReturnType()).isEqualTo(void.class);
        assertThat(iface.getMethod("start").getReturnType()).isEqualTo(void.class);
        assertThat(iface.getMethod("shutdown").getReturnType()).isEqualTo(void.class);
        assertThat(iface.getMethod("getDescription").getReturnType()).isEqualTo(String.class);
    }
}
