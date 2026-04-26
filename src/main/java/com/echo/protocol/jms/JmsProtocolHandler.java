package com.echo.protocol.jms;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.jms.JmsConnectionManager;
import com.echo.protocol.AbstractProtocolHandler;
import com.echo.repository.JmsRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JMS 協定處理器
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
public class JmsProtocolHandler extends AbstractProtocolHandler {

    private final JmsRuleRepository repository;
    private final Optional<JmsConnectionManager> jmsConnectionManager;

    @Autowired
    public JmsProtocolHandler(JmsRuleRepository repository, 
                              @Autowired(required = false) JmsConnectionManager jmsConnectionManager) {
        this.repository = repository;
        this.jmsConnectionManager = Optional.ofNullable(jmsConnectionManager);
    }

    @Override
    public Protocol getProtocol() {
        return Protocol.JMS;
    }

    @Override
    public RuleDto toDto(BaseRule rule, Response response, boolean includeBody) {
        JmsRule r = (JmsRule) rule;
        var builder = RuleDto.builder()
                .id(r.getId())
                .version(r.getVersion())
                .protocol(Protocol.JMS)
                .matchKey(r.getQueueName())
                .bodyCondition(r.getBodyCondition())
                .priority(r.getPriority())
                .description(r.getDescription())
                .enabled(r.getEnabled())
                .isProtected(r.getIsProtected())
                .tags(r.getTags())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .extendedAt(r.getExtendedAt())
                .condition(r.getCondition())
                .responseId(r.getResponseId())
                .delayMs(r.getDelayMs())
                .maxDelayMs(r.getMaxDelayMs())
                .faultType(r.getFaultType() != null ? r.getFaultType().name() : "NONE")
                .scenarioName(r.getScenarioName())
                .requiredScenarioState(r.getRequiredScenarioState())
                .newScenarioState(r.getNewScenarioState());
        if (includeBody && response != null) {
            builder.responseBody(response.getBody());
        }
        return builder.build();
    }

    @Override
    public BaseRule fromDto(RuleDto dto) {
        return JmsRule.builder()
                .id(dto.getId())
                .version(dto.getVersion())
                .queueName(dto.getMatchKey())
                .bodyCondition(dto.getBodyCondition())
                .priority(dto.getPriority() != null ? dto.getPriority() : 0)
                .description(dto.getDescription() != null && !dto.getDescription().isBlank() ? dto.getDescription() : generateRuleDescription(dto))
                .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                .isProtected(dto.getIsProtected() != null ? dto.getIsProtected() : false)
                .tags(dto.getTags())
                .responseId(dto.getResponseId())
                .delayMs(dto.getDelayMs() != null ? dto.getDelayMs() : 0L)
                .maxDelayMs(dto.getMaxDelayMs())
                .faultType(parseFaultType(dto.getFaultType()))
                .scenarioName(dto.getScenarioName())
                .requiredScenarioState(dto.getRequiredScenarioState())
                .newScenarioState(dto.getNewScenarioState())
                .createdAt(dto.getCreatedAt()) // 保留建立時間
                .build();
    }

    @Override
    public List<JmsRule> findAllRules() {
        return repository.findAll();
    }

    @Override
    public Optional<JmsRule> findById(String id) {
        return repository.findById(id);
    }

    @Override
    public List<JmsRule> findAllByIds(List<String> ids) {
        return repository.findAllById(ids);
    }


    @Override
    public BaseRule save(BaseRule rule) {
        return repository.save((JmsRule) rule);
    }

    @Override
    public void deleteById(String id) {
        repository.deleteById(id);
    }

    /**
     * 查詢 JMS 規則（含萬用 fallback）
     */
    public List<JmsRule> findWithFallback(String queueName) {
        return repository.findByQueueNameOrWildcard(queueName);
    }

    @Override
    public int deleteAll() {
        int count = (int) repository.count();
        repository.deleteAll();
        return count;
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public int deleteExpiredRules(LocalDateTime cutoff) {
        return repository.deleteExpiredRules(cutoff);
    }

    @Override
    public List<Object[]> countGroupByResponseId() {
        return repository.countGroupByResponseId();
    }

    @Override
    public int deleteByResponseId(Long responseId) {
        return repository.deleteByResponseId(responseId);
    }

    @Override
    public List<JmsRule> findByResponseId(Long responseId) {
        return repository.findByResponseId(responseId);
    }

    @Override
    public long countOrphanRules() {
        return repository.countOrphanRules();
    }

    @Override
    public int updateEnabled(List<String> ids, boolean enabled) {
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.updateEnabledByIds(ids, enabled);
    }

    @Override
    public int updateProtected(List<String> ids, boolean isProtected) {
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.updateProtectedByIds(ids, isProtected);
    }

    @Override
    public int extendRules(List<String> ids, LocalDateTime extendedAt) {
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.extendByIds(ids, extendedAt);
    }

    @Override
    public String generateDescription(RuleDto dto) {
        return "JMS: " + (dto.getMatchKey() != null ? dto.getMatchKey() : "規則");
    }

    private FaultType parseFaultType(String value) {
        if (value == null || value.isBlank()) {
            return FaultType.NONE;
        }
        try {
            return FaultType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return FaultType.NONE;
        }
    }

    private String generateRuleDescription(RuleDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("JMS ").append(dto.getMatchKey() != null ? dto.getMatchKey() : "QUEUE");
        int condCount = 0;
        if (dto.getBodyCondition() != null && !dto.getBodyCondition().isBlank()) {
            condCount += dto.getBodyCondition().split(";").length;
        }
        if (condCount > 0) {
            sb.append(" [").append(condCount).append(condCount == 1 ? " condition" : " conditions").append("]");
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> testRule(BaseRule rule, Map<String, Object> request) {
        JmsRule jmsRule = (JmsRule) rule;
        if (jmsConnectionManager.isEmpty() || jmsConnectionManager.get().getJmsTemplate() == null) {
            return Map.of("status", 503, "body", "JMS not connected", "elapsed", 0);
        }
        
        long start = System.currentTimeMillis();
        try {
            String body = (String) request.getOrDefault("body", "");
            int timeout = ((Number) request.getOrDefault("timeout", 30)).intValue();
            
            var jms = jmsConnectionManager.get();
            var jmsTemplate = jms.getJmsTemplate();
            jmsTemplate.setReceiveTimeout(timeout * 1000L);
            
            String queueName = jmsRule.getQueueName().equals("*") ? "ECHO.REQUEST" : jmsRule.getQueueName();
            
            jakarta.jms.Message response = jmsTemplate.sendAndReceive(queueName, 
                session -> session.createTextMessage(body));
            
            String responseBody = response instanceof jakarta.jms.TextMessage tm ? tm.getText() : "(no reply)";
            long elapsed = System.currentTimeMillis() - start;
            
            return Map.of("status", 200, "body", responseBody, "elapsed", elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return Map.of("status", 500, "body", "Error: " + e.getMessage(), "elapsed", elapsed);
        }
    }
}
