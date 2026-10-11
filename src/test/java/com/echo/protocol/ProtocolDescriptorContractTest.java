package com.echo.protocol;

import com.echo.config.CacheConfig;
import com.echo.entity.Protocol;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.protocol.jms.JmsProtocolHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 每個協定的描述都必須與其 entity、快取設定一致；新增協定時這裡會抓到欄位或快取名稱打錯。 */
class ProtocolDescriptorContractTest {

    static Stream<ProtocolDescriptor> descriptors() {
        return Stream.of(HttpProtocolHandler.DESCRIPTOR, JmsProtocolHandler.DESCRIPTOR);
    }

    @Test
    void everyProtocolHasADescriptor() {
        assertThat(descriptors().map(ProtocolDescriptor::id)).containsExactlyInAnyOrder(Protocol.values());
    }

    @ParameterizedTest
    @MethodSource("descriptors")
    void rulesCacheIsAKnownRuleCache(ProtocolDescriptor descriptor) {
        assertThat(CacheConfig.ALL_RULE_CACHES).contains(descriptor.rulesCacheName());
    }

    @ParameterizedTest
    @MethodSource("descriptors")
    void searchFieldsExistOnTheRuleEntity(ProtocolDescriptor descriptor) {
        assertThat(descriptor.searchFields()).isNotEmpty();
        for (String name : descriptor.searchFields()) {
            assertThat(field(descriptor.ruleType(), name))
                    .as("%s.%s", descriptor.ruleType().getSimpleName(), name).isPresent();
        }
    }

    @ParameterizedTest
    @MethodSource("descriptors")
    void actionsMatchTheEntityActionType(ProtocolDescriptor descriptor) {
        Class<?> actionType = field(descriptor.ruleType(), "action").orElseThrow().getType();
        assertThat(descriptor.mockAction()).isInstanceOf(actionType).hasToString("MOCK");
        assertThat(descriptor.forwardAction()).isInstanceOf(actionType).hasToString("FORWARD");
    }

    @Test
    void cacheEventTypesKeepTheExistingWireNames() {
        assertThat(HttpProtocolHandler.DESCRIPTOR.cacheEventType()).isEqualTo("HTTP_RULE_CHANGED");
        assertThat(JmsProtocolHandler.DESCRIPTOR.cacheEventType()).isEqualTo("JMS_RULE_CHANGED");
    }

    @Test
    void registryExposesOnlyEnabledProtocols() {
        ProtocolHandler http = mock(ProtocolHandler.class);
        when(http.getProtocol()).thenReturn(Protocol.HTTP);
        when(http.descriptor()).thenReturn(HttpProtocolHandler.DESCRIPTOR);

        ProtocolHandlerRegistry registry = new ProtocolHandlerRegistry(List.of(http));

        assertThat(registry.descriptors()).containsExactly(HttpProtocolHandler.DESCRIPTOR);
        assertThat(registry.descriptor(Protocol.HTTP)).contains(HttpProtocolHandler.DESCRIPTOR);
        assertThat(registry.descriptor(Protocol.JMS)).isEmpty();
    }

    private static Optional<Field> field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return Optional.of(c.getDeclaredField(name));
            } catch (NoSuchFieldException ignored) {
                // 往父類別找
            }
        }
        return Optional.empty();
    }
}
