package com.echo.service;

import com.echo.entity.Scenario;
import com.echo.repository.ScenarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scenario state machine service
 * <p>
 * Manage the status query, transfer, reset and cleanup of Scenario.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScenarioService {

    public static final String DEFAULT_STATE = "Started";

    private final ScenarioRepository scenarioRepository;

    /**
     * Query the current status of Scenario, and return the default status "Started" if it does not exist.
     */
    public String getCurrentState(String scenarioName) {
        return scenarioRepository.findByScenarioName(scenarioName)
                .map(Scenario::getCurrentState)
                .orElse(DEFAULT_STATE);
    }

    /**
     * CAS semantic update Scenario status.
     * If Scenario does not exist, create it first and then update it.
     *
     * @return true if the status transfer is successful
     */
    @Transactional
    public boolean advanceState(String scenarioName, String expectedState, String newState) {
        int updated = scenarioRepository.updateStateIfMatch(scenarioName, expectedState, newState);
        if (updated > 0) {
            log.debug("Scenario '{}' state advanced: {} -> {}", scenarioName, expectedState, newState);
            return true;
        }

        // Scenario does not exist, try to create it
        if (scenarioRepository.findByScenarioName(scenarioName).isEmpty()) {
            try {
                Scenario scenario = Scenario.builder()
                        .scenarioName(scenarioName)
                        .currentState(DEFAULT_STATE)
                        .build();
                scenarioRepository.save(scenario);
                log.debug("Scenario '{}' created with state '{}'", scenarioName, DEFAULT_STATE);
            } catch (DataIntegrityViolationException e) {
                // Another thread has been created, ignored
                log.debug("Scenario '{}' already created by another thread", scenarioName);
            }

            // Try CAS update again
            updated = scenarioRepository.updateStateIfMatch(scenarioName, expectedState, newState);
            if (updated > 0) {
                log.debug("Scenario '{}' state advanced after creation: {} -> {}",
                        scenarioName, expectedState, newState);
                return true;
            }
        }

        log.debug("Scenario '{}' state advance failed: expected={}, newState={}",
                scenarioName, expectedState, newState);
        return false;
    }

    /**
     * Reset single Scenario to default state
     */
    @Transactional
    public void resetScenario(String scenarioName) {
        scenarioRepository.findByScenarioName(scenarioName).ifPresent(s -> {
            s.setCurrentState(DEFAULT_STATE);
            scenarioRepository.save(s);
            log.info("Scenario '{}' reset to '{}'", scenarioName, DEFAULT_STATE);
        });
    }

    /**
     * Reset all Scenario to default state
     */
    @Transactional
    public void resetAll() {
        List<Scenario> all = scenarioRepository.findAll();
        for (Scenario s : all) {
            s.setCurrentState(DEFAULT_STATE);
        }
        scenarioRepository.saveAll(all);
        log.info("All {} scenarios reset to '{}'", all.size(), DEFAULT_STATE);
    }

    /**
     * List all Scenario
     */
    public List<Scenario> findAll() {
        return scenarioRepository.findAll();
    }

    /**
     * Delete Scenario which is no longer referenced by any rules
     */
    @Transactional
    public int deleteOrphans(Set<String> activeScenarioNames) {
        List<Scenario> all = scenarioRepository.findAll();
        List<Scenario> orphans = all.stream()
                .filter(s -> !activeScenarioNames.contains(s.getScenarioName()))
                .toList();

        if (!orphans.isEmpty()) {
            scenarioRepository.deleteAll(orphans);
            log.info("Deleted {} orphan scenarios: {}", orphans.size(),
                    orphans.stream().map(Scenario::getScenarioName).collect(Collectors.joining(", ")));
        }
        return orphans.size();
    }
}
