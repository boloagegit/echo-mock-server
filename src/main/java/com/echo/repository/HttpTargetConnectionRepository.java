package com.echo.repository;

import com.echo.entity.HttpTargetConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HttpTargetConnectionRepository extends JpaRepository<HttpTargetConnection, Long> {
    List<HttpTargetConnection> findAllByOrderByNameAsc();
    Optional<HttpTargetConnection> findFirstByDefaultConnectionTrueAndEnabledTrue();
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
