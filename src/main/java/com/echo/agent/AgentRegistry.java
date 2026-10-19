package com.echo.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry for agents.
 * <p>
 * Automatically collect all {@link EchoAgent} implementations and provide a unified query interface.
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
     * Get all registered agents.
     */
    public List<EchoAgent> getAll() {
        return agents;
    }

    /**
     * Get an agent by name.
     */
    public Optional<EchoAgent> getByName(String name) {
        return Optional.ofNullable(agentMap.get(name));
    }
}
