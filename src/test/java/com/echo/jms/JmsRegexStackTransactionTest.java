package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.entity.JmsRuleAction;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.service.*;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JmsRegexStackTransactionTest {
    @Test
    void regexStackFailureKeepsSourceRollbackWithoutReplyOrDownstreamSideEffects() throws Exception {
        var properties = new JmsProperties();
        var rules = mock(JmsRuleService.class);
        var forwarder = mock(JmsTargetForwarder.class);
        var rule = JmsRule.builder().id("recursive-regex").enabled(true).queueName(properties.getQueue())
                .matchSpec("{\"source\":\"header\",\"name\":\"probe\",\"op\":\"matches\",\"value\":\"^(a|aa)+$\"}")
                .responseId(1L).priority(0).action(JmsRuleAction.MOCK).build();
        when(rules.findPreparedJmsRules(properties.getQueue())).thenReturn(List.of(rule));
        var pipeline = new JmsMockPipeline(new ConditionMatcher(), mock(RuleService.class),
                mock(RequestLogService.class), rules, forwarder, properties, mock(ScenarioService.class));
        var manager = mock(JmsConnectionManager.class);
        var template = mock(JmsTemplate.class);
        when(manager.getJmsTemplate()).thenReturn(template);
        var budget = new JmsMessageMemoryBudget(1048576, 8);
        var listener = new MockJmsListener(manager, properties, pipeline, new JmsEndpointExtractor(), budget);
        var request = mock(TextMessage.class);
        when(request.getText()).thenReturn("{}");
        when(request.getJMSReplyTo()).thenReturn(mock(Queue.class));
        when(request.getPropertyNames()).thenReturn(Collections.enumeration(List.of("probe")));
        when(request.getObjectProperty("probe")).thenReturn("a".repeat(10000));
        var session = mock(Session.class);
        when(session.getTransacted()).thenReturn(true);
        var container = new TestContainer(listener::onMessage);
        var worker = Executors.newSingleThreadExecutor(task -> new Thread(null, task, "jms-regex-capacity-test", 128 * 1024L));
        try {
            worker.submit(() -> { container.deliver(session, request); return null; }).get(5, TimeUnit.SECONDS);
            verify(session).rollback();
            verify(session, never()).commit();
            verifyNoInteractions(template, forwarder);
            assertThat(budget.reservedBytes()).isZero();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class TestContainer extends DefaultMessageListenerContainer {
        TestContainer(MessageListener listener) {
            setConnectionFactory(mock(jakarta.jms.ConnectionFactory.class));
            setMessageListener(listener);
            setSessionTransacted(true);
            setAcceptMessagesWhileStopping(true);
        }
        void deliver(Session session, Message message) throws jakarta.jms.JMSException { executeListener(session, message); }
    }
}
