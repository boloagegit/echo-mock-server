package com.echo.service;

import com.echo.entity.RequestLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.type.Type;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * SQLite-only insert path for new replayed logs. Uses the current Hibernate connection,
 * mapping and value binders, without enrolling each log in the persistence context.
 * Checkpoint/retention remain in the caller's existing Spring-managed transaction.
 */
@Repository
@ConditionalOnProperty(name = "echo.request-log.jdbc-batch-enabled", havingValue = "true")
public class RequestLogJdbcBatchInsert {
    private final AbstractEntityPersister mapping;
    private final String insertSql;
    private final List<Property> properties;

    @PersistenceContext
    private EntityManager entityManager;

    public RequestLogJdbcBatchInsert(EntityManagerFactory entityManagerFactory) {
        var factory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        if (!(factory.getJdbcServices().getDialect() instanceof SQLiteDialect)) {
            mapping = null;
            insertSql = null;
            properties = List.of();
            return;
        }
        var descriptor = (AbstractEntityPersister) factory.getMappingMetamodel().getEntityDescriptor(RequestLog.class);
        var columns = new StringJoiner(", ");
        var placeholders = new StringJoiner(", ");
        var selected = new ArrayList<Property>();
        Type[] types = descriptor.getPropertyTypes();
        boolean[] insertable = descriptor.getPropertyInsertability();
        for (int index = 0; index < types.length; index++) {
            if (!insertable[index]) continue;
            String[] names = descriptor.getPropertyColumnNames(index);
            if (names.length != 1) {
                // A future composite property must retain its JPA semantics rather than
                // preventing startup or being partially inserted by this narrow adapter.
                mapping = null;
                insertSql = null;
                properties = List.of();
                return;
            }
            columns.add(names[0]);
            placeholders.add("?");
            selected.add(new Property(index, types[index]));
        }
        mapping = descriptor;
        properties = List.copyOf(selected);
        insertSql = "INSERT INTO " + mapping.getTableName() + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    /** Existing IDs retain JPA merge behavior. Generated IDs are not needed by spool replay. */
    public boolean insertNew(List<RequestLog> logs) {
        if (mapping == null || logs.stream().anyMatch(log -> log.getId() != null)) return false;
        if (logs.isEmpty()) return true;
        // JDBC bypasses entity callbacks; use the same normalization/default-time implementation.
        logs.forEach(RequestLog::onCreate);
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        session.doWork(connection -> {
            try (var statement = connection.prepareStatement(insertSql)) {
                for (RequestLog log : logs) {
                    Object[] values = mapping.getPropertyValues(log);
                    for (int index = 0; index < properties.size(); index++) {
                        Property property = properties.get(index);
                        property.type().nullSafeSet(statement, values[property.index()], index + 1, session);
                    }
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                if (counts.length != logs.size()) {
                    throw new IllegalStateException("Request-log batch did not confirm every insert");
                }
                for (int count : counts) {
                    if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                        throw new IllegalStateException("Request-log batch insert failed: " + count);
                    }
                }
            }
        });
        return true;
    }

    private record Property(int index, Type type) { }
}
