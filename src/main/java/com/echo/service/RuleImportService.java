package com.echo.service;

import com.echo.dto.RuleDto;
import com.echo.dto.RuleImportPreview;
import com.echo.dto.RuleImportPreview.ConnectionOption;
import com.echo.dto.RuleImportPreview.ConnectionRequirement;
import com.echo.dto.RuleImportPreview.Problem;
import com.echo.dto.RuleImportRequest;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Copies portable rules without treating source database identities as destination references. */
@Service
@RequiredArgsConstructor
public class RuleImportService {
    private final RuleEditService ruleEditService;
    private final ResponseService responseService;
    private final ProtocolHandlerRegistry protocols;
    private final Optional<HttpTargetConnectionService> httpConnections;
    private final Optional<JmsTargetConnectionService> jmsConnections;

    public record ImportedRule(int row, String sourceId, String id, Protocol protocol) {}

    public record Result(int imported, int responsesCreated, List<ImportedRule> rules) {
        public Result {
            rules = List.copyOf(rules);
        }
    }

    private record Row(int number, RuleDto source, RuleDto copy) {}

    private record ResponseContent(String body, String description, String type) {}

    private record Plan(RuleImportPreview preview, List<Row> rows) {}

    @Transactional(readOnly = true)
    public RuleImportPreview preview(RuleImportRequest request) {
        return plan(request).preview();
    }

    /** Revalidate inside the write transaction; any failure rolls back the entire batch. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Result importRules(RuleImportRequest request) {
        Plan plan = plan(request);
        if (!plan.preview().errors().isEmpty()) {
            throw new RuleImportValidationException(plan.preview().errors());
        }
        Map<Long, Long> copiedResponses = new HashMap<>();
        List<ImportedRule> imported = new ArrayList<>();
        int responsesCreated = 0;
        for (Row row : plan.rows()) {
            RuleDto dto = row.copy();
            if (needsResponse(dto)) {
                Long originalResponseId = row.source().getResponseId();
                Long responseId =
                        originalResponseId == null ? null : copiedResponses.get(originalResponseId);
                if (responseId == null) {
                    String description = dto.getResponseDescription();
                    if (row.source().getExportVersion() == null
                            && (description == null || description.isBlank())) {
                        description = protocols.generateDescription(dto);
                    }
                    Response response =
                            responseService.save(
                                    Response.builder()
                                            .body(dto.getResponseBody())
                                            .description(description)
                                            .contentType(responseType(dto))
                                            .build());
                    responseId = response.getId();
                    responsesCreated++;
                    if (originalResponseId != null) {
                        copiedResponses.put(originalResponseId, responseId);
                    }
                }
                dto.setResponseId(responseId);
                dto.setResponseBody(null);
            }
            RuleEditService.Saved saved = ruleEditService.save(dto);
            imported.add(
                    new ImportedRule(
                            row.number(),
                            row.source().getId(),
                            saved.rule().getId(),
                            dto.getProtocol()));
        }
        return new Result(imported.size(), responsesCreated, List.copyOf(imported));
    }

    private Plan plan(RuleImportRequest request) {
        List<Problem> errors = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        if (request == null || request.rules() == null || request.rules().isEmpty()) {
            errors.add(new Problem(0, "RULES_REQUIRED", "Select at least one rule to import."));
            return new Plan(
                    new RuleImportPreview(0, 0, 0, List.of(), List.of(), List.copyOf(errors)),
                    rows);
        }
        Map<String, String> mappings =
                request.connectionMappings() == null ? Map.of() : request.connectionMappings();
        List<ConnectionOption> options = connectionOptions(request.rules());
        Map<String, ConnectionOption> selections = new HashMap<>();
        options.forEach(option -> selections.put(option.selection(), option));
        Map<String, ConnectionRequirement> requirements = new LinkedHashMap<>();
        Map<Long, ResponseContent> sourceResponses = new HashMap<>();
        int enabled = 0;
        for (int index = 0; index < request.rules().size(); index++) {
            int number = index + 1;
            RuleDto source = request.rules().get(index);
            if (source == null
                    || source.getProtocol() == null
                    || !protocols.isEnabled(source.getProtocol())) {
                errors.add(
                        new Problem(
                                number,
                                "PROTOCOL_UNAVAILABLE",
                                "The rule's protocol is missing or disabled in this environment."));
                continue;
            }
            if (source.getExportVersion() != null && source.getExportVersion() != 1) {
                errors.add(
                        new Problem(
                                number,
                                "UNSUPPORTED_EXPORT_VERSION",
                                "This export version is not supported."));
                continue;
            }
            RuleDto dto = new RuleDto();
            BeanUtils.copyProperties(source, dto);
            try {
                validateSourceModes(dto);
            } catch (IllegalArgumentException e) {
                errors.add(new Problem(number, "INVALID_RULE", e.getMessage()));
                continue;
            }
            dto.setMatchSpec(
                    source.getMatchSpec() == null ? null : source.getMatchSpec().deepCopy());
            // Every import is a new local rule; retention uses the new creation timestamp.
            dto.setId(null);
            dto.setVersion(null);
            dto.setCreatedAt(null);
            dto.setUpdatedAt(null);
            dto.setExtendedAt(null);
            dto.setCreatedBy(null);
            dto.setUpdatedBy(null);
            dto.setResponseId(null);
            dto.setUpdateExistingResponse(false);
            dto.setExportVersion(null);
            dto.setSourceConnectionName(null);
            if (!Boolean.FALSE.equals(dto.getEnabled())) {
                enabled++;
            }
            if (needsResponse(dto)) {
                if (source.getResponseId() != null
                        && source.getResponseBody() == null
                        && source.getExportVersion() == null) {
                    errors.add(
                            new Problem(
                                    number,
                                    "RESPONSE_BODY_MISSING",
                                    "The file contains only a source response ID. Re-export it with"
                                            + " complete response content."));
                    continue;
                }
                if (source.getResponseId() != null) {
                    ResponseContent content =
                            new ResponseContent(
                                    dto.getResponseBody(),
                                    dto.getResponseDescription(),
                                    responseType(dto));
                    ResponseContent previous =
                            sourceResponses.putIfAbsent(source.getResponseId(), content);
                    if (previous != null && !Objects.equals(previous, content)) {
                        errors.add(
                                new Problem(
                                        number,
                                        "SHARED_RESPONSE_MISMATCH",
                                        "Rules referring to the same source response contain"
                                                + " different content or metadata."));
                        continue;
                    }
                }
            }
            String key = connectionKey(source);
            if (key != null) {
                ConnectionRequirement previous = requirements.get(key);
                requirements.put(
                        key,
                        new ConnectionRequirement(
                                key,
                                source.getProtocol(),
                                source.getSourceConnectionName() == null
                                        ? source.getForwardTargetMode()
                                        : source.getSourceConnectionName(),
                                previous == null ? 1 : previous.rules() + 1));
                ConnectionOption choice = selections.get(mappings.get(key));
                if (choice == null || choice.protocol() != source.getProtocol()) {
                    errors.add(
                            new Problem(
                                    number,
                                    "CONNECTION_SELECTION_REQUIRED",
                                    "Choose a current destination connection for this forwarding"
                                            + " rule."));
                    continue;
                }
                dto.setForwardTargetMode(choice.mode());
                dto.setHttpTargetConnectionId(null);
                dto.setJmsTargetConnectionId(null);
                if ("CONNECTION".equals(choice.mode())) {
                    if (dto.getProtocol() == Protocol.HTTP) {
                        dto.setHttpTargetConnectionId(Long.valueOf(choice.id()));
                    } else {
                        dto.setJmsTargetConnectionId(choice.id());
                    }
                }
            }
            try {
                ruleEditService.validateImport(dto);
                rows.add(new Row(number, source, dto));
            } catch (IllegalArgumentException e) {
                errors.add(new Problem(number, "INVALID_RULE", e.getMessage()));
            }
        }
        return new Plan(
                new RuleImportPreview(
                        request.rules().size(),
                        enabled,
                        request.rules().size() - enabled,
                        List.copyOf(requirements.values()),
                        options,
                        List.copyOf(errors)),
                rows);
    }

    private List<ConnectionOption> connectionOptions(List<RuleDto> rules) {
        boolean http =
                rules.stream()
                        .anyMatch(
                                rule ->
                                        rule != null
                                                && rule.getProtocol() == Protocol.HTTP
                                                && connectionKey(rule) != null);
        boolean jms =
                rules.stream()
                        .anyMatch(
                                rule ->
                                        rule != null
                                                && rule.getProtocol() == Protocol.JMS
                                                && connectionKey(rule) != null);
        List<ConnectionOption> options = new ArrayList<>();
        if (http) {
            options.add(
                    new ConnectionOption(
                            "HTTP:ORIGINAL_HOST",
                            Protocol.HTTP,
                            "ORIGINAL_HOST",
                            null,
                            "X-Original-Host"));
            httpConnections.ifPresent(
                    service ->
                            service.list().stream()
                                    .filter(connection -> connection.enabled())
                                    .forEach(
                                            connection -> {
                                                options.add(
                                                        option(
                                                                Protocol.HTTP,
                                                                "CONNECTION",
                                                                connection.id().toString(),
                                                                connection.name(),
                                                                connection.version()));
                                                if (connection.defaultConnection()) {
                                                    options.add(
                                                            option(
                                                                    Protocol.HTTP,
                                                                    "DEFAULT_CONNECTION",
                                                                    connection.id().toString(),
                                                                    connection.name(),
                                                                    connection.version()));
                                                }
                                            }));
        }
        if (jms) {
            jmsConnections.ifPresent(
                    service -> {
                        service.list().stream()
                                .filter(connection -> connection.enabled() && !connection.legacy())
                                .forEach(
                                        connection -> {
                                            options.add(
                                                    option(
                                                            Protocol.JMS,
                                                            "CONNECTION",
                                                            connection.id(),
                                                            connection.name(),
                                                            connection.version()));
                                        });
                        service.findActiveSummary()
                                .ifPresent(
                                        connection ->
                                                options.add(
                                                        option(
                                                                Protocol.JMS,
                                                                "DEFAULT_CONNECTION",
                                                                connection.id(),
                                                                connection.name(),
                                                                connection.version())));
                    });
        }
        return List.copyOf(options);
    }

    private static ConnectionOption option(
            Protocol protocol, String mode, String id, String name, Long version) {
        return new ConnectionOption(
                protocol + ":" + mode + ":" + id + ":" + version, protocol, mode, id, name);
    }

    private static void validateSourceModes(RuleDto rule) {
        String action = rule.getAction();
        if (action != null && !action.isBlank()) {
            action = action.toUpperCase(Locale.ROOT);
            if (!"MOCK".equals(action) && !"FORWARD".equals(action)) {
                throw new IllegalArgumentException("Unsupported action: " + rule.getAction());
            }
            rule.setAction(action);
        }
        String mode = rule.getForwardTargetMode();
        if (mode != null && !mode.isBlank()) {
            mode = mode.toUpperCase(Locale.ROOT);
            boolean allowed =
                    "CONNECTION".equals(mode)
                            || "DEFAULT_CONNECTION".equals(mode)
                            || (rule.getProtocol() == Protocol.HTTP
                                    && "ORIGINAL_HOST".equals(mode));
            if (!allowed) {
                throw new IllegalArgumentException(
                        "Unsupported forwardTargetMode: " + rule.getForwardTargetMode());
            }
            rule.setForwardTargetMode(mode);
        }
    }

    /** Source identities are only grouping keys, never destination lookup keys. */
    public static String connectionKey(RuleDto rule) {
        if (!"FORWARD".equalsIgnoreCase(rule.getAction()) || isFaulting(rule)) {
            return null;
        }
        String mode = rule.getForwardTargetMode();
        if (mode == null || mode.isBlank()) {
            mode = rule.getProtocol() == Protocol.HTTP ? "ORIGINAL_HOST" : "DEFAULT_CONNECTION";
        }
        mode = mode.toUpperCase(Locale.ROOT);
        Object id =
                rule.getProtocol() == Protocol.HTTP
                        ? rule.getHttpTargetConnectionId()
                        : rule.getJmsTargetConnectionId();
        return rule.getProtocol() + ":" + mode + ("CONNECTION".equals(mode) ? ":" + id : "");
    }

    private static boolean isFaulting(RuleDto rule) {
        return rule.getFaultType() != null
                && !rule.getFaultType().isBlank()
                && !"NONE".equals(rule.getFaultType());
    }

    private static boolean needsResponse(RuleDto rule) {
        return !isFaulting(rule) && !"FORWARD".equalsIgnoreCase(rule.getAction());
    }

    private static String responseType(RuleDto rule) {
        return Boolean.TRUE.equals(rule.getSseEnabled())
                ? Response.SSE
                : Response.normalizeContentType(rule.getResponseContentType());
    }
}
