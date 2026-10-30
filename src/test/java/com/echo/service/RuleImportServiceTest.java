package com.echo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.echo.config.JmsProperties;
import com.echo.dto.RuleDto;
import com.echo.dto.RuleImportRequest;
import com.echo.entity.JmsForwardTargetMode;
import com.echo.entity.JmsRule;
import com.echo.entity.JmsRuleAction;
import com.echo.entity.JmsTargetConnection;
import com.echo.entity.Protocol;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.JmsRuleRepository;
import com.echo.repository.JmsTargetConnectionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class RuleImportServiceTest {
    @Mock RuleEditService edits;
    @Mock ResponseService responses;
    @Mock ProtocolHandlerRegistry protocols;
    @Mock JmsTargetConnectionRepository connections;
    @Mock JmsRuleRepository rules;
    @Mock JmsCredentialCipher cipher;

    private JmsProperties properties;
    private JmsTargetConnectionService targets;
    private RuleImportService imports;

    @BeforeEach
    void setUp() {
        properties = new JmsProperties();
        targets = new JmsTargetConnectionService(connections, rules, cipher, properties, List.of());
        imports =
                new RuleImportService(
                        edits, responses, protocols, Optional.empty(), Optional.of(targets));
        when(protocols.isEnabled(Protocol.JMS)).thenReturn(true);
    }

    private JmsTargetConnection profile(long id, String name) {
        return JmsTargetConnection.builder()
                .id(id)
                .version(7L)
                .name(name)
                .providerType("artemis")
                .serverUrl("tcp://127.0.0.1:1")
                .queueName("DB.QUEUE")
                .timeoutSeconds(5)
                .enabled(true)
                .defaultConnection(true)
                .build();
    }

    private RuleImportRequest request() {
        return new RuleImportRequest(
                List.of(
                        RuleDto.builder()
                                .protocol(Protocol.JMS)
                                .matchKey("SOURCE.QUEUE")
                                .action("FORWARD")
                                .forwardTargetMode("DEFAULT_CONNECTION")
                                .build()),
                Map.of());
    }

    @Test
    void yamlDefaultShownInPreviewMatchesRuntimeEvenWithADatabaseDefault() {
        var database = profile(1, "Database default");
        when(connections.findAllByOrderByNameAsc()).thenReturn(List.of(database));
        properties.getTarget().setEnabled(true);
        properties.getTarget().setName("YAML actual default");
        properties.getTarget().setServerUrl("tcp://127.0.0.1:2");
        properties.getTarget().setQueue("YAML.QUEUE");

        var preview = imports.preview(request());
        var defaults =
                preview.connections().stream()
                        .filter(c -> c.mode().equals("DEFAULT_CONNECTION"))
                        .toList();
        assertThat(defaults).hasSize(1);
        assertThat(defaults.get(0).id()).isEqualTo(JmsTargetConnectionService.LEGACY_ID);
        assertThat(defaults.get(0).name()).isEqualTo(targets.resolveActive().orElseThrow().name());
        assertThat(preview.connections())
                .anyMatch(c -> c.mode().equals("CONNECTION") && c.id().equals("1"));

        var exporter =
                new RuleEditService(
                        protocols,
                        responses,
                        mock(ResponseContentValidatorRegistry.class),
                        mock(CacheManager.class),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(targets));
        var source =
                JmsRule.builder()
                        .id("source")
                        .action(JmsRuleAction.FORWARD)
                        .forwardTargetMode(JmsForwardTargetMode.DEFAULT_CONNECTION)
                        .build();
        doReturn(Optional.of(source)).when(protocols).findById("source");
        when(protocols.toDto(source, null, true)).thenReturn(request().rules().get(0));
        assertThat(exporter.findExportDto("source").orElseThrow().getSourceConnectionName())
                .isEqualTo(defaults.get(0).name());
    }

    @Test
    void previewUsesTheRuntimeSelectedDatabaseDefaultEvenForLegacyMultipleDefaultRows() {
        var firstByName = profile(2, "Alphabetically first but not active");
        var actual = profile(1, "Runtime selected default");
        when(connections.findAllByOrderByNameAsc()).thenReturn(List.of(firstByName, actual));
        when(connections.findFirstByDefaultConnectionTrueAndEnabledTrue())
                .thenReturn(Optional.of(actual));

        var defaults =
                imports.preview(request()).connections().stream()
                        .filter(c -> c.mode().equals("DEFAULT_CONNECTION"))
                        .toList();
        assertThat(defaults).hasSize(1);
        assertThat(defaults.get(0).id()).isEqualTo("1");
        assertThat(defaults.get(0).name()).isEqualTo(targets.resolveActive().orElseThrow().name());
    }
}
