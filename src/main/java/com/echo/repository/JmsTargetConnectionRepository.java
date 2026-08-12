package com.echo.repository;

import com.echo.entity.JmsTargetConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JmsTargetConnectionRepository extends JpaRepository<JmsTargetConnection, Long> {
    List<JmsTargetConnection> findAllByOrderByNameAsc();
    Optional<JmsTargetConnection> findFirstByDefaultConnectionTrueAndEnabledTrue();
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
