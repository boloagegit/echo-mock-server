package com.echo.repository;

import com.echo.entity.RequestLogCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RequestLogCheckpointRepository
        extends JpaRepository<RequestLogCheckpoint, String> {
}
