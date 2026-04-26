package com.echo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * JMS 協定模擬規則
 * <p>
 * 定義 JMS 訊息的匹配條件與回應設定：
 * <ul>
 *   <li>匹配條件：queueName + correlationIdPattern + bodyCondition</li>
 *   <li>回應設定：responseBody/responseId + delayMs</li>
 * </ul>
 * 
 * <h3>Queue 名稱匹配</h3>
 * <ul>
 *   <li>精確匹配：ORDER.REQUEST</li>
 *   <li>萬用字元：ORDER.* 匹配 ORDER.CREATE, ORDER.UPDATE 等</li>
 *   <li>全部匹配：* 匹配所有 Queue</li>
 * </ul>
 */
@Entity
@Table(name = "jms_rules", indexes = {
    @Index(name = "idx_jms_rule_queue", columnList = "queueName"),
    @Index(name = "idx_jms_rule_enabled", columnList = "enabled"),
    @Index(name = "idx_jms_rule_response", columnList = "response_id")
})
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class JmsRule extends BaseRule {

    /** 
     * Queue 名稱匹配模式
     * <p>支援萬用字元 * 匹配任意字串
     */
    @Column(nullable = false)
    private String queueName;

    /** 
     * Correlation ID 匹配模式
     * <p>用於 Request-Reply 模式的訊息關聯
     */
    private String correlationIdPattern;

    @Override
    public Protocol getProtocol() {
        return Protocol.JMS;
    }

    /**
     * 取得條件字串（向後相容）
     * @return bodyCondition 值
     */
    @Transient
    public String getCondition() {
        return getBodyCondition();
    }

    /**
     * 取得匹配鍵值（向後相容）
     * @return queueName 值
     */
    @Transient
    public String getMatchKey() {
        return queueName;
    }
}
