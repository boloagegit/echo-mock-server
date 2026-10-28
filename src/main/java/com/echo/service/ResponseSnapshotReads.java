package com.echo.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Short metadata/body read transactions, never response execution or JMS transactions. */
@Component
public final class ResponseSnapshotReads {
    private final TransactionTemplate reads;
    private final Counter fallbackReads;

    public ResponseSnapshotReads(
            PlatformTransactionManager manager, DataSource dataSource, MeterRegistry meters)
            throws SQLException {
        String product;
        try (var connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        }
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // SQL Server RR permits phantoms; its serializable fallback can take range locks.
        reads.setIsolationLevel(
                product.contains("postgres")
                                || product.contains("mysql")
                                || product.contains("maria")
                        ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                        : TransactionDefinition.ISOLATION_SERIALIZABLE);
        fallbackReads = meters.counter("echo.response.consistency.fallbacks");
    }

    public <T> T snapshot(Supplier<T> selection) {
        return reads.execute(status -> selection.get());
    }

    public <T> T fallback(Supplier<T> selection) {
        fallbackReads.increment();
        return snapshot(selection);
    }
}
