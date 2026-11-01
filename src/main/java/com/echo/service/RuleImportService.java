package com.echo.service;

import com.echo.dto.RuleDto;
import com.echo.dto.RuleImportPreview;
import com.echo.dto.RuleImportPreview.ConnectionOption;
import com.echo.dto.RuleImportPreview.ConnectionRequirement;
import com.echo.dto.RuleImportPreview.Problem;
import com.echo.dto.RuleImportPreview.RowSummary;
import com.echo.dto.RuleImportRequest;
import com.echo.entity.BaseRule;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Copies portable rules without treating source database identities as destination references. */
@Service
@RequiredArgsConstructor
public class RuleImportService {
    private final RuleEditService ruleEditService;
    private final ResponseService responseService;
    private final ProtocolHandlerRegistry protocols;
    private final Optional<HttpTargetConnectionService> httpConnections;
    private final Optional<JmsTargetConnectionService> jmsConnections;
    private final Optional<RuleAuditService> audits;

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
        return plan(request, true).preview();
    }

    /** Revalidate inside the write transaction; any failure rolls back the entire batch. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Result importRules(RuleImportRequest request) {
        Plan plan = plan(request, false);
        if (!plan.preview().errors().isEmpty()) {
            throw new RuleImportValidationException(plan.preview().errors());
        }
        Map<Long, Long> copiedResponses = new HashMap<>();
        List<ImportedRule> imported = new ArrayList<>();
        int responsesCreated = 0;
        for (Row row : plan.rows()) {
            RuleDto dto = row.copy();
            Long originalResponseId = row.source().getResponseId();
            if (needsResponse(dto)) {
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
            audits.ifPresent(audit -> audit.logCreate(saved.rule()));
            imported.add(
                    new ImportedRule(
                            row.number(),
                            row.source().getId(),
                            saved.rule().getId(),
                            dto.getProtocol()));
        }
        return new Result(imported.size(), responsesCreated, List.copyOf(imported));
    }

    private Plan plan(RuleImportRequest request, boolean review) {
        List<Problem> errors = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<RowSummary> summaries = new ArrayList<>();
        Set<Integer> excluded =
                request == null || request.excludedRows() == null
                        ? Set.of()
                        : request.excludedRows();
        List<RuleDto> included = new ArrayList<>();
        if (request != null && request.rules() != null) {
            for (int index = 0; index < request.rules().size(); index++) {
                if (!excluded.contains(index + 1)) {
                    included.add(request.rules().get(index));
                }
            }
        }
        Set<String> existing = review ? existingSignatures() : Set.of();
        if (included.isEmpty()) {
            if (review && request != null && request.rules() != null) {
                for (int index = 0; index < request.rules().size(); index++) {
                    summaries.add(summary(index + 1, request.rules().get(index), existing));
                }
            }
            errors.add(new Problem(0, "RULES_REQUIRED", "Select at least one rule to import."));
            return new Plan(
                    new RuleImportPreview(
                            request == null || request.rules() == null
                                    ? 0
                                    : request.rules().size(),
                            0,
                            0,
                            List.of(),
                            List.of(),
                            List.copyOf(errors),
                            List.copyOf(summaries)),
                    rows);
        }
        Map<String, String> mappings =
                request.connectionMappings() == null ? Map.of() : request.connectionMappings();
        List<ConnectionOption> options = connectionOptions(included);
        Map<String, ConnectionOption> selections = new HashMap<>();
        options.forEach(option -> selections.put(option.selection(), option));
        Map<String, Requirement> requirements = new LinkedHashMap<>();
        Map<Long, ResponseContent> sourceResponses = new HashMap<>();
        int enabled = 0;
        int disabled = 0;
        for (int index = 0; index < request.rules().size(); index++) {
            int number = index + 1;
            RuleDto source = request.rules().get(index);
            if (review) {
                summaries.add(summary(number, source, existing));
            }
            if (excluded.contains(number)) {
                continue;
            }
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
            boolean connectionChosen = true;
            String key = connectionKey(source);
            if (key != null) {
                requirements
                        .computeIfAbsent(
                                key,
                                ignored ->
                                        new Requirement(
                                                key,
                                                source.getProtocol(),
                                                modeOf(key),
                                                source.getSourceConnectionName(),
                                                new ArrayList<>()))
                        .rows()
                        .add(number);
                ConnectionOption choice = selections.get(mappings.get(key));
                dto.setHttpTargetConnectionId(null);
                dto.setJmsTargetConnectionId(null);
                if (choice == null || choice.protocol() != source.getProtocol()) {
                    connectionChosen = false;
                } else {
                    dto.setForwardTargetMode(choice.mode());
                    if ("CONNECTION".equals(choice.mode())) {
                        if (dto.getProtocol() == Protocol.HTTP) {
                            dto.setHttpTargetConnectionId(Long.valueOf(choice.id()));
                        } else {
                            dto.setJmsTargetConnectionId(choice.id());
                        }
                    }
                }
            }
            try {
                // Without a chosen destination the rule's own content is still checked for review.
                ruleEditService.validateImport(dto, connectionChosen);
            } catch (IllegalArgumentException e) {
                errors.add(new Problem(number, "INVALID_RULE", e.getMessage()));
                continue;
            }
            if (Boolean.FALSE.equals(dto.getEnabled())) {
                disabled++;
            } else {
                enabled++;
            }
            if (!connectionChosen) {
                errors.add(
                        new Problem(
                                number,
                                "CONNECTION_SELECTION_REQUIRED",
                                "Choose a current destination connection for this forwarding"
                                        + " rule."));
                continue;
            }
            rows.add(new Row(number, source, dto));
        }
        List<ConnectionRequirement> requirementList =
                requirements.values().stream()
                        .map(requirement -> requirement.toPreview(suggest(requirement, options)))
                        .toList();
        return new Plan(
                new RuleImportPreview(
                        request.rules().size(),
                        enabled,
                        disabled,
                        requirementList,
                        options,
                        List.copyOf(errors),
                        List.copyOf(summaries)),
                rows);
    }

    private record Requirement(
            String key, Protocol protocol, String mode, String sourceName, List<Integer> rows) {
        ConnectionRequirement toPreview(String suggestion) {
            return new ConnectionRequirement(
                    key, protocol, mode, sourceName, rows.size(), List.copyOf(rows), suggestion);
        }
    }

    private static String modeOf(String key) {
        return key.split(":", 3)[1];
    }

    /**
     * A preselection the user still confirms: X-Original-Host stays dynamic, a source default
     * follows this environment's default, and a named connection matches exactly one destination
     * connection with the same name.
     */
    private static String suggest(Requirement requirement, List<ConnectionOption> options) {
        List<ConnectionOption> candidates =
                options.stream()
                        .filter(option -> option.protocol() == requirement.protocol())
                        .filter(
                                option ->
                                        "CONNECTION".equals(requirement.mode())
                                                ? "CONNECTION".equals(option.mode())
                                                        && requirement.sourceName() != null
                                                        && requirement
                                                                .sourceName()
                                                                .equals(option.name())
                                                : requirement.mode().equals(option.mode()))
                        .toList();
        return candidates.size() == 1 ? candidates.get(0).selection() : null;
    }

    private Set<String> existingSignatures() {
        Set<String> signatures = new HashSet<>();
        for (BaseRule rule : protocols.findAllRules()) {
            if (protocols.isEnabled(rule.getProtocol())) {
                signatures.add(signature(protocols.toDto(rule, null, false)));
            }
        }
        return signatures;
    }

    private static RowSummary summary(int number, RuleDto source, Set<String> existing) {
        if (source == null) {
            return new RowSummary(number, null, null, null, null, null, false, "MOCK", null, false);
        }
        String action =
                isFaulting(source)
                        ? "FAULT"
                        : "FORWARD".equalsIgnoreCase(source.getAction()) ? "FORWARD" : "MOCK";
        return new RowSummary(
                number,
                source.getProtocol(),
                source.getProtocol() == Protocol.HTTP ? source.getMethod() : null,
                source.getMatchKey(),
                source.getTargetHost(),
                source.getDescription(),
                !Boolean.FALSE.equals(source.getEnabled()),
                action,
                source.getProtocol() == null ? null : connectionKey(source),
                source.getProtocol() != null && existing.contains(signature(source)));
    }

    /** Rules with the same signature match the same requests in the same scenario state, whatever their responses. */
    private static String signature(RuleDto rule) {
        return String.join(
                "\u0001",
                String.valueOf(rule.getProtocol()),
                normalized(rule.getTargetHost()),
                normalized(rule.getMatchKey()),
                rule.getProtocol() == Protocol.HTTP
                        ? normalized(rule.getMethod()).toUpperCase(Locale.ROOT)
                        : "",
                normalized(rule.getBodyCondition()),
                normalized(rule.getQueryCondition()),
                normalized(rule.getHeaderCondition()),
                rule.getMatchSpec() == null || rule.getMatchSpec().isNull()
                        ? ""
                        : rule.getMatchSpec().toString(),
                // Rules gated on different scenario states are eligible at different times.
                normalized(rule.getScenarioName()),
                normalized(rule.getRequiredScenarioState()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
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
