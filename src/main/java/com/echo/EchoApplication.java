package com.echo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Echo Mock Server 主程式入口
 * <p>
 * 提供 HTTP/JMS 協定的模擬服務，支援：
 * <ul>
 *   <li>規則式請求匹配與回應</li>
 *   <li>條件式路由（Body/Query 參數匹配）</li>
 *   <li>共用回應管理</li>
 *   <li>請求日誌與稽核追蹤</li>
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
