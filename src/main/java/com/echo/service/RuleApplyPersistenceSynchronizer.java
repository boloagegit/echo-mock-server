package com.echo.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/** Ensure that the Apply response brings back an updated optimistic locking version of the database. */
@Component
public class RuleApplyPersistenceSynchronizer {

    @PersistenceContext
    private EntityManager entityManager;

    public void flush() {
        entityManager.flush();
    }
}
