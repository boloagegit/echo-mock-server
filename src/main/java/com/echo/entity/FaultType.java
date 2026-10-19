package com.echo.entity;

/**
 * Fault injection type
 * <p>
 * After the rule is successfully matched, the response behavior is determined based on faultType:
 * <ul>
 *   <li>NONE — Normal response (default)</li>
 *   <li>CONNECTION_RESET — HTTP: Close the connection and do not respond; JMS: Do not send reply</li>
 *   <li>EMPTY_RESPONSE — HTTP: Return 200 empty body; JMS: Send empty reply</li>
 * </ul>
 */
public enum FaultType {
    NONE,
    CONNECTION_RESET,
    EMPTY_RESPONSE
}
