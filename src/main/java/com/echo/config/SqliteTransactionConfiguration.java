package com.echo.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** Provides SQLite-specific transaction coordination without operator tuning. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${spring.datasource.url:}'.startsWith('jdbc:sqlite:')")
public class SqliteTransactionConfiguration {

    @Bean(name = "transactionManager")
    @Primary
    PlatformTransactionManager sqliteTransactionManager(
            EntityManagerFactory entityManagerFactory,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new SqliteSerializingJpaTransactionManager(
                entityManagerFactory, meterRegistryProvider.getIfAvailable());
    }
}
