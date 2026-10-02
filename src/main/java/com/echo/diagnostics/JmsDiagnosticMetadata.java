package com.echo.diagnostics;

import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.Topic;

/** Read only existing JMS metadata. Failure cannot change delivery or invoke provider toString(). */
public final class JmsDiagnosticMetadata {
    private JmsDiagnosticMetadata() { }

    public static String destination(Destination destination) {
        if (destination == null) return "-";
        try {
            if (destination instanceof Queue queue) return queue.getQueueName();
            if (destination instanceof Topic topic) return topic.getTopicName();
        } catch (JMSException | RuntimeException ignored) { return "unavailable"; }
        return destination.getClass().getSimpleName();
    }

    public static void inbound(TransactionDiagnostics.Trace trace, Message message, String queue) {
        if (!trace.enabled()) return;
        String messageId = null;
        try {
            messageId = message.getJMSMessageID();
            trace.event("RECEIVED", "queue", queue, "messageId", messageId,
                    "correlationId", message.getJMSCorrelationID(),
                    "replyTo", destination(message.getJMSReplyTo()),
                    "redelivered", message.getJMSRedelivered(), "deliveryCount",
                    message.propertyExists("JMSXDeliveryCount") ? message.getIntProperty("JMSXDeliveryCount") : null);
        } catch (JMSException | RuntimeException error) {
            trace.failure("METADATA_UNAVAILABLE", error);
            trace.event("RECEIVED", "queue", queue, "messageId", messageId, "metadataPartial", true);
        }
    }

    public static void message(TransactionDiagnostics.Trace trace, String event, Message message) {
        if (!trace.enabled()) return;
        String messageId = null;
        try {
            messageId = message.getJMSMessageID();
            trace.event(event, "messageId", messageId,
                    "correlationId", message.getJMSCorrelationID(), "messageType", message.getClass().getSimpleName());
        } catch (JMSException | RuntimeException error) {
            trace.failure("METADATA_UNAVAILABLE", error);
            trace.event(event, "messageId", messageId, "messageType", message.getClass().getSimpleName(),
                    "metadataPartial", true);
        }
    }

    public static void outbound(TransactionDiagnostics.Trace trace, Message message, Destination replyTo) {
        if (!trace.enabled()) return;
        String messageId = null;
        try {
            messageId = message.getJMSMessageID();
            trace.event("OUTBOUND_SEND_RETURNED", "messageId", messageId,
                    "correlationId", message.getJMSCorrelationID(), "replyTo", destination(replyTo));
        } catch (JMSException | RuntimeException error) {
            trace.failure("METADATA_UNAVAILABLE", error);
            trace.event("OUTBOUND_SEND_RETURNED", "messageId", messageId,
                    "replyTo", destination(replyTo), "metadataPartial", true);
        }
    }

    public static void reply(TransactionDiagnostics.Trace trace, Message response, long waitMs) {
        if (!trace.enabled()) return;
        String messageId = null;
        String outcome = response instanceof jakarta.jms.TextMessage ? "TEXT_REPLY" : "INVALID_TYPE";
        try {
            messageId = response.getJMSMessageID();
            trace.event("REPLY_WAIT_END", "outcome", outcome,
                    "messageId", messageId, "correlationId", response.getJMSCorrelationID(),
                    "messageType", response.getClass().getSimpleName(), "waitMs", waitMs);
        } catch (JMSException | RuntimeException error) {
            trace.failure("METADATA_UNAVAILABLE", error);
            trace.event("REPLY_WAIT_END", "outcome", outcome, "messageId", messageId,
                    "messageType", response.getClass().getSimpleName(), "waitMs", waitMs, "metadataPartial", true);
        }
    }
}
