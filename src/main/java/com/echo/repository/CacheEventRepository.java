package com.echo.repository;

import com.echo.entity.CacheEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface CacheEventRepository extends JpaRepository<CacheEvent, Long> {

    List<CacheEvent> findByTimestampAfter(LocalDateTime timestamp);

    @Modifying
    @Query("DELETE FROM CacheEvent e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
