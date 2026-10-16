package com.echo.controller;

import com.echo.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 情境（Scenario）狀態的查詢與重設。需開啟 {@code echo.features.scenarios-enabled}；關閉時回 404。
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService scenarioService;

    @Value("${echo.features.scenarios-enabled:false}")
    private boolean scenariosEnabled;

    @GetMapping("/scenarios")
    public ResponseEntity<?> listScenarios() {
        if (!scenariosEnabled) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(scenarioService.findAll());
    }

    @PutMapping("/scenarios/{name}/reset")
    public ResponseEntity<?> resetScenario(@PathVariable String name) {
        if (!scenariosEnabled) return ResponseEntity.notFound().build();
        scenarioService.resetScenario(name);
        return ResponseEntity.ok(Map.of("success", true, "scenarioName", name));
    }

    @PutMapping("/scenarios/reset")
    public ResponseEntity<?> resetAllScenarios() {
        if (!scenariosEnabled) return ResponseEntity.notFound().build();
        scenarioService.resetAll();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
