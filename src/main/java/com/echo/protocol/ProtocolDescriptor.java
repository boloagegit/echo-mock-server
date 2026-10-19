package com.echo.protocol;

import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;

import java.util.List;
import java.util.Set;

/**
 * Information required by a protocol in protocol-independent programs (rule lookup, cache invalidation, management API) is provided by the protocol's {@link ProtocolHandler}.
 * When adding a new protocol, it is described in handler. There is no need to add protocol branches to each service.
 *
 * @param id             Protocol
 * @param ruleType       Rule entity class, used for rule-list queries.
 * @param rulesCacheName Rule cache name; cleared when rules change and synchronize multiple instances
 * @param searchFields   Rule list keyword search field (entity attribute name)
 * @param mockAction     The rule {@code action} field represents the value of "return mock"
 * @param forwardAction  The rule {@code action} field represents the value of "forward"
 * @param matchSources   Value sources that can be used for structured conditions ({@code matchSpec}), such as {@code body}, {@code header}
 */
public record ProtocolDescriptor(Protocol id,
                                 Class<? extends BaseRule> ruleType,
                                 String rulesCacheName,
                                 List<String> searchFields,
                                 Enum<?> mockAction,
                                 Enum<?> forwardAction,
                                 Set<String> matchSources) {

    public ProtocolDescriptor {
        searchFields = List.copyOf(searchFields);
        matchSources = Set.copyOf(matchSources);
    }

    /** When synchronizing multiple instances, the event type represents "the rules of this protocol have changed". */
    public String cacheEventType() {
        return cacheEventType(id);
    }

    public static String cacheEventType(Protocol protocol) {
        return protocol.name() + "_RULE_CHANGED";
    }
}
