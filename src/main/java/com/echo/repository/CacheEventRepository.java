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

    /** 刪除舊事件，但保留編號最大的一筆，避免編號在資料表清空後重新開始。 */
    @Modifying
    @Query("DELETE FROM CacheEvent e WHERE e.timestamp < :cutoff AND e.id < :keepId")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("keepId") Long keepId);
}
