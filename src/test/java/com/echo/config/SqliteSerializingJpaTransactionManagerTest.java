package com.echo.config;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteSerializingJpaTransactionManagerTest {

    @Test
    void beginFailureReleasesWriter() {
        EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
        when(entityManagerFactory.createEntityManager())
                .thenThrow(new PersistenceException("cannot open entity manager"));
        ExposedTransactionManager transactionManager =
                new ExposedTransactionManager(entityManagerFactory);

        Object transaction = transactionManager.newTransaction();
        TransactionDefinition definition = new DefaultTransactionDefinition();

        assertThatThrownBy(() -> transactionManager.begin(transaction, definition))
                .isInstanceOf(RuntimeException.class);
        assertThat(transactionManager.getActiveWriterCount()).isZero();
        assertThat(transactionManager.getQueuedWriterCount()).isZero();
    }

    private static final class ExposedTransactionManager
            extends SqliteSerializingJpaTransactionManager {

        private static final long serialVersionUID = 1L;

        private ExposedTransactionManager(EntityManagerFactory entityManagerFactory) {
            super(entityManagerFactory, null);
        }

        private Object newTransaction() {
            return doGetTransaction();
        }

        private void begin(Object transaction, TransactionDefinition definition) {
            doBegin(transaction, definition);
        }
    }
}
