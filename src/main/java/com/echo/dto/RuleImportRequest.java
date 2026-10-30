package com.echo.dto;

import java.util.List;
import java.util.Map;

/** Rules to copy, with explicit choices from the destination environment's preview. */
public record RuleImportRequest(List<RuleDto> rules, Map<String, String> connectionMappings) {}
