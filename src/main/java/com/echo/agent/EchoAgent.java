package com.echo.agent;

/**
 * Common interface for background agents, defining lifecycle and monitoring contracts.
 */
public interface EchoAgent {

    /**
     * Get the agent name.
     */
    String getName();

    /**
     * Get the agent description explaining its purpose.
     */
    String getDescription();

    /**
     * Get the current agent status.
     */
    AgentStatus getStatus();

    /**
     * Get agent statistics: queue size, processed count, and dropped count.
     */
    AgentStats getStats();

    /**
     * Submit a task to the agent.
     * Reject tasks unless the agent is RUNNING.
     *
     * @param task Task to be submitted
     */
    void submit(Object task);

    /**
     * Start the agent.
     */
    void start();

    /**
     * Shut down the agent, perform a final flush, and transition to STOPPED.
     */
    void shutdown();
}
