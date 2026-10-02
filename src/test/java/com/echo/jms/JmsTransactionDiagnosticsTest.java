package com.echo.jms;

import com.echo.config.DiagnosticProperties;
import com.echo.config.JmsProperties;
import com.echo.diagnostics.JmsDiagnosticMetadata;
import com.echo.diagnostics.TransactionDiagnostics;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import jakarta.jms.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JmsTransactionDiagnosticsTest {
    @Test
    void unmatchedForwardKeepsAlreadyParsedServiceNameWithoutChangingForwardedBody() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var properties = new JmsProperties();
            var forwarder = mock(JmsTargetForwarder.class);
            var rules = mock(com.echo.service.JmsRuleService.class);
            when(rules.findBucketedJmsRules("ECHO.REQUEST", "CreateOrder")).thenReturn(List.of());
            when(forwarder.hasActiveTarget()).thenReturn(true);
            when(forwarder.forwardWithMetadata(eq("PRIVATE_BODY"), isNull(), any(TransactionDiagnostics.Trace.class)))
                    .thenAnswer(call -> {
                        TransactionDiagnostics.Trace trace = call.getArgument(2);
                        trace.event("REPLY_WAIT_END", "outcome", "NO_REPLY", "waitMs", 30000);
                        return new JmsTargetForwarder.ForwardResult("<error>JMS response timeout</error>", "fixture-target");
                    });
            var pipeline = new JmsMockPipeline(mock(com.echo.service.ConditionMatcher.class),
                    mock(com.echo.service.RuleService.class), mock(com.echo.service.RequestLogService.class),
                    rules, forwarder, properties, mock(com.echo.service.ScenarioService.class));
            var trace = diagnostics.begin("JMS");
            var result = pipeline.execute(MockRequest.builder().protocol(com.echo.entity.Protocol.JMS)
                    .path("ECHO.REQUEST | CreateOrder").endpointValue("CreateOrder").body("PRIVATE_BODY")
                    .trace(trace).build());
            trace.end("PROCESSING_ENDED");
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.size() == 1);
            assertThat(lines.get(0)).contains("endpoint=CreateOrder", "mode=FORWARD", "waitResult=NO_REPLY")
                    .doesNotContain("PRIVATE_BODY");
            assertThat(result.getResponse().getBody()).isEqualTo("<error>JMS response timeout</error>");
            verify(forwarder).forwardWithMetadata("PRIVATE_BODY", null, trace);
        }
    }

    @Test
    void unsupportedMetadataNeverHidesSendOrReplyOutcome() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var message = mock(BytesMessage.class);
            when(message.getJMSMessageID()).thenReturn("ID:partial");
            when(message.getJMSCorrelationID()).thenThrow(new JMSException("private-provider-detail"));
            var trace = diagnostics.begin("JMS");
            JmsDiagnosticMetadata.outbound(trace, message, null);
            JmsDiagnosticMetadata.reply(trace, message, 123);
            JmsDiagnosticMetadata.message(trace, "REPLY_SEND_RETURNED", message);
            trace.end("TEST_ENDED");
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.stream().anyMatch(line -> line.contains("event=END")));
            assertThat(String.join("\n", lines)).contains("sendReturned=true", "callerReply=SEND_RETURNED",
                    "waitResult=INVALID_TYPE", "outId=ID:partial", "waitMs=123", "metadataPartial=true")
                    .doesNotContain("private-provider-detail");
        }
    }

    @Test
    void unsupportedCorrelationHeaderRetainsInboundIdAndNeverChangesReply() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var manager = mock(JmsConnectionManager.class);
            var template = mock(JmsTemplate.class);
            var pipeline = mock(JmsMockPipeline.class);
            var request = mock(TextMessage.class);
            var replyTo = mock(Queue.class);
            when(request.getJMSMessageID()).thenReturn("ID:inbound");
            when(request.getJMSCorrelationID()).thenThrow(new JMSException("byte correlation, not string"));
            when(request.getText()).thenReturn("request");
            when(request.getJMSReplyTo()).thenReturn(replyTo);
            when(manager.getJmsTemplate()).thenReturn(template);
            when(pipeline.execute(any())).thenReturn(PipelineResult.builder().matched(true)
                    .response(MockResponse.builder().status(200).body("reply").matched(true).build()).build());
            var listener = new MockJmsListener(manager, new JmsProperties(), pipeline, new JmsEndpointExtractor(),
                    new JmsMessageMemoryBudget(64 * 1024 * 1024L, 8));
            listener.setDiagnostics(diagnostics);
            listener.onMessage(request);
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.stream().anyMatch(line -> line.contains("event=END")));
            assertThat(String.join("\n", lines)).contains("METADATA_UNAVAILABLE", "inId=ID:inbound",
                    "metadataPartial=true", "callerReply=SEND_RETURNED").doesNotContain("byte correlation");
            verify(template).send(eq(replyTo), any(MessageCreator.class));
        }
    }
    enum Outcome { TEXT, NO_REPLY, INVALID_TYPE, SEND_FAILED, RECEIVE_FAILED, CLEANUP_FAILED,
        CONNECT_FAILED, SESSION_FAILED, CONSUMER_FAILED }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void forwardingOutcomesAreDistinctWithoutChangingPayloadHeadersOrCleanup(Outcome outcome) throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var properties = new JmsProperties();
            properties.getTarget().setEnabled(true);
            properties.getTarget().setType("artemis");
            properties.getTarget().setServerUrl("tcp://user:password@localhost:61616?secret=hidden");
            properties.getTarget().setQueue("TARGET.REQUEST");
            properties.getTarget().setTimeoutSeconds(1);
            ConnectionFactory factory = mock(ConnectionFactory.class);
            Connection connection = mock(Connection.class);
            Session session = mock(Session.class);
            Queue destination = mock(Queue.class);
            TemporaryQueue temporary = mock(TemporaryQueue.class);
            MessageProducer producer = mock(MessageProducer.class);
            MessageConsumer consumer = mock(MessageConsumer.class);
            TextMessage outbound = mock(TextMessage.class);
            TextMessage response = mock(TextMessage.class);
            JmsTargetFactoryProvider provider = mock(JmsTargetFactoryProvider.class);
            when(provider.supports(anyString())).thenReturn(true);
            when(provider.create(any())).thenReturn(factory);
            when(factory.createConnection()).thenReturn(connection);
            when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
            when(session.createQueue("TARGET.REQUEST")).thenReturn(destination);
            when(session.createTemporaryQueue()).thenReturn(temporary);
            when(temporary.getQueueName()).thenReturn("temporary-1");
            when(session.createProducer(destination)).thenReturn(producer);
            when(session.createTextMessage("PRIVATE_BODY")).thenReturn(outbound);
            when(session.createConsumer(temporary)).thenReturn(consumer);
            when(outbound.getJMSMessageID()).thenReturn("ID:outbound");
            when(response.getJMSMessageID()).thenReturn("ID:response");
            when(response.getText()).thenReturn("PRIVATE_REPLY");
            when(consumer.receive(1000)).thenReturn(response);
            switch (outcome) {
                case NO_REPLY -> when(consumer.receive(1000)).thenReturn(null);
                case INVALID_TYPE -> when(consumer.receive(1000)).thenReturn(mock(BytesMessage.class));
                case SEND_FAILED -> doThrow(new JMSException("private-provider-message")).when(producer).send(outbound);
                case RECEIVE_FAILED -> when(consumer.receive(1000)).thenThrow(new JMSException("private-provider-message"));
                case CLEANUP_FAILED -> doThrow(new JMSException("private-provider-message")).when(temporary).delete();
                case CONNECT_FAILED -> when(factory.createConnection()).thenThrow(new JMSException("private-provider-message", "CONNECT_CODE"));
                case SESSION_FAILED -> when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenThrow(new JMSException("private-provider-message"));
                case CONSUMER_FAILED -> when(session.createConsumer(temporary)).thenThrow(new JMSException("private-provider-message"));
                default -> { }
            }
            var forwarder = new JmsTargetForwarder(properties, List.of(provider));
            try {
                var trace = diagnostics.begin("JMS");
                var result = forwarder.forwardWithMetadata("PRIVATE_BODY", null, trace);
                trace.end("TEST_ENDED");
                if (outcome != Outcome.TEXT) await().atMost(2, TimeUnit.SECONDS).until(() -> lines.size() == 1);
                String log = String.join("\n", lines);
                if (outcome != Outcome.TEXT) assertThat(log).contains("targetQueue=TARGET.REQUEST", "timeoutMs=1000");
                assertThat(log).doesNotContain("PRIVATE_BODY", "PRIVATE_REPLY", "private-provider-message", "password", "secret=hidden");
                if (outcome != Outcome.CONNECT_FAILED && outcome != Outcome.SESSION_FAILED) verify(outbound).setJMSReplyTo(temporary);
                verify(outbound, never()).setJMSCorrelationID(any());
                if (outcome != Outcome.CONNECT_FAILED && outcome != Outcome.SESSION_FAILED) {
                    verify(temporary).delete(); verify(session).close();
                }
                switch (outcome) {
                    case TEXT, CLEANUP_FAILED -> {
                        assertThat(result.body()).isEqualTo("PRIVATE_REPLY");
                        if (outcome == Outcome.CLEANUP_FAILED) assertThat(log).contains("sendReturned=true", "outId=ID:outbound", "outReplyTo=temporary-1",
                                "replyId=ID:response", "waitResult=TEXT_REPLY", "cleanupFailure=true");
                        else assertThat(diagnostics.snapshot()).containsEntry("diagnosticAccepted", 0L);
                    }
                    case NO_REPLY -> {
                        assertThat(result.body()).isEqualTo("<error>JMS response timeout</error>");
                        assertThat(log).contains("waitResult=NO_REPLY", "possibleConsumerClose=true").doesNotContain("waitResult=INVALID_TYPE");
                    }
                    case INVALID_TYPE -> {
                        assertThat(result.body()).isEqualTo("<error>JMS response timeout</error>");
                        assertThat(log).contains("BytesMessage", "waitResult=INVALID_TYPE").doesNotContain("waitResult=NO_REPLY");
                    }
                    case SEND_FAILED -> assertThat(log).contains("FORWARD_FAILED", "stage=SEND").doesNotContain("sendReturned=true");
                    case RECEIVE_FAILED -> assertThat(log).contains("sendReturned=true", "FORWARD_FAILED", "stage=RECEIVE_REPLY");
                    case CONNECT_FAILED -> assertThat(log).contains("stage=CONNECT", "providerCode=CONNECT_CODE");
                    case SESSION_FAILED -> assertThat(log).contains("stage=CREATE_SESSION");
                    case CONSUMER_FAILED -> assertThat(log).contains("stage=CREATE_CONSUMER", "sendReturned=true");
                }
                if (outcome == Outcome.CLEANUP_FAILED) assertThat(log).contains("TEMP_QUEUE_CLEANUP_FAILED");
            } finally { forwarder.cleanup(); }
        }
    }

    @Test
    void listenerCorrelatesRedeliveryAttemptsAndReplyFailuresWithoutReadingBodyForDiagnostics() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var properties = new JmsProperties();
            var manager = mock(JmsConnectionManager.class);
            var pipeline = mock(JmsMockPipeline.class);
            var template = mock(JmsTemplate.class);
            var listener = new MockJmsListener(manager, properties, pipeline, new JmsEndpointExtractor(),
                    new JmsMessageMemoryBudget(64 * 1024 * 1024L, 8));
            listener.setDiagnostics(diagnostics);
            TextMessage request = mock(TextMessage.class);
            Queue replyTo = mock(Queue.class);
            when(request.getJMSMessageID()).thenReturn("ID:inbound");
            when(request.getJMSCorrelationID()).thenReturn("caller-correlation");
            when(request.propertyExists("JMSXDeliveryCount")).thenReturn(true);
            when(request.getIntProperty("JMSXDeliveryCount")).thenReturn(1, 2);
            when(request.getJMSRedelivered()).thenReturn(false, true);
            when(request.getJMSReplyTo()).thenReturn(replyTo);
            when(replyTo.getQueueName()).thenReturn("caller-reply");
            when(request.getText()).thenReturn("<ServiceName>demo</ServiceName>");
            when(manager.getJmsTemplate()).thenReturn(template);
            when(pipeline.execute(any(MockRequest.class))).thenAnswer(call -> {
                MockRequest parsed = call.getArgument(0);
                parsed.getTrace().event("ROUTE", "mode", "MOCK", "endpoint", parsed.getEndpointValue());
                return PipelineResult.builder().response(MockResponse.builder().status(200).body("reply").matched(true).build())
                        .matched(true).ruleId("rule-1").build();
            });
            listener.onMessage(request);
            doThrow(new java.lang.IllegalStateException("secret-provider-message")).when(template).send(eq(replyTo), any(MessageCreator.class));
            listener.onMessage(request);
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.size() == 1);
            String log = String.join("\n", lines);
            assertThat(log).contains("inId=ID:inbound", "deliveryCount=2", "redelivered=true",
                    "callerReply=SEND_FAILED", "REPLY_SEND_FAILED").doesNotContain("secret-provider-message", "<ServiceName>");
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticAccepted", 1L);
            verify(request, times(2)).getText(); // only the original business extraction
        }
    }
}
