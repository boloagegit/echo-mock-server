package com.echo.repository;

import com.echo.entity.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    Optional<Scenario> findByScenarioName(String scenarioName);

    void deleteByScenarioName(String scenarioName);

    /** CAS 更新：只有當前狀態符合預期時才更新 */
    @Modifying
    @Query("UPDATE Scenario s SET s.currentState = :newState, s.updatedAt = CURRENT_TIMESTAMP, s.version = s.version + 1 " +
           "WHERE s.scenarioName = :name AND s.currentState = :expectedState")
    int updateStateIfMatch(@Param("name") String scenarioName,
                           @Param("expectedState") String expectedState,
                           @Param("newState") String newState);
}
