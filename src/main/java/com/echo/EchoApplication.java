package com.echo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Echo Mock Server Main program entrance
 * <p>
 * Provides simulation services of HTTP/JMS protocol, supporting:
 * <ul>
 *   <li>rule-based request matching and response</li>
 *   <li>conditional routing (Body/Query parameter matching)</li>
 *   <li>shared response management</li>
 *   <li>request log and audit trail</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class EchoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoApplication.class, args);
    }
}
