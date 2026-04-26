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
 * Scenario 狀態機服務
 * <p>
 * 管理 Scenario 的狀態查詢、轉移、重置與清理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScenarioService {

    public static final String DEFAULT_STATE = "Started";

    private final ScenarioRepository scenarioRepository;

    /**
     * 查詢 Scenario 當前狀態，不存在時回傳預設狀態 "Started"
     */
    public String getCurrentState(String scenarioName) {
        return scenarioRepository.findByScenarioName(scenarioName)
                .map(Scenario::getCurrentState)
                .orElse(DEFAULT_STATE);
    }

    /**
     * CAS 語意更新 Scenario 狀態。
     * 如果 Scenario 不存在，先建立再更新。
     *
     * @return true 如果狀態轉移成功
     */
    @Transactional
    public boolean advanceState(String scenarioName, String expectedState, String newState) {
        int updated = scenarioRepository.updateStateIfMatch(scenarioName, expectedState, newState);
        if (updated > 0) {
            log.debug("Scenario '{}' state advanced: {} -> {}", scenarioName, expectedState, newState);
            return true;
        }

        // Scenario 不存在，嘗試建立
        if (scenarioRepository.findByScenarioName(scenarioName).isEmpty()) {
            try {
                Scenario scenario = Scenario.builder()
                        .scenarioName(scenarioName)
                        .currentState(DEFAULT_STATE)
                        .build();
                scenarioRepository.save(scenario);
                log.debug("Scenario '{}' created with state '{}'", scenarioName, DEFAULT_STATE);
            } catch (DataIntegrityViolationException e) {
                // 另一個執行緒已建立，忽略
                log.debug("Scenario '{}' already created by another thread", scenarioName);
            }

            // 再次嘗試 CAS 更新
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
     * 重置單一 Scenario 為預設狀態
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
     * 重置全部 Scenario 為預設狀態
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
     * 列出所有 Scenario
     */
    public List<Scenario> findAll() {
        return scenarioRepository.findAll();
    }

    /**
     * 刪除不再被任何規則引用的 Scenario
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
