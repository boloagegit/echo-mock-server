package com.echo.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/** 確保 Apply 回應帶回資料庫已更新的樂觀鎖版本。 */
@Component
public class RuleApplyPersistenceSynchronizer {

    @PersistenceContext
    private EntityManager entityManager;

    public void flush() {
        entityManager.flush();
    }
}
