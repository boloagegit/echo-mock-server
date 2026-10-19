package com.echo.repository;

import com.echo.entity.CacheEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CacheEventRepository extends JpaRepository<CacheEvent, Long> {

    List<CacheEvent> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    List<CacheEvent> findByIdIn(Collection<Long> ids);

    @Query("SELECT MAX(e.id) FROM CacheEvent e")
    Long findMaxId();

    /** Delete old events, but keep the one with the largest number to prevent the numbering from restarting after the data table is cleared. */
    @Modifying
    @Query("DELETE FROM CacheEvent e WHERE e.timestamp < :cutoff AND e.id < :keepId")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);
}
