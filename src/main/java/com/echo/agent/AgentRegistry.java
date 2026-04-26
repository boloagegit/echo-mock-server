package com.echo.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 中央註冊中心。
 * <p>
 * 自動收集所有 {@link EchoAgent} 實作，提供統一查詢介面。
 */
@Component
public class AgentRegistry {

    private final List<EchoAgent> agents;
    private final Map<String, EchoAgent> agentMap;

    public AgentRegistry(List<EchoAgent> agentBeans) {
        this.agents = List.copyOf(agentBeans);
        this.agentMap = agentBeans.stream()
                .collect(Collectors.toMap(EchoAgent::getName, Function.identity()));
    }

    /**
     * 取得所有已註冊 agent。
     */
    public List<EchoAgent> getAll() {
        return agents;
    }

    /**
     * 依名稱取得 agent。
     */
    public Optional<EchoAgent> getByName(String name) {
        return Optional.ofNullable(agentMap.get(name));
    }
}
