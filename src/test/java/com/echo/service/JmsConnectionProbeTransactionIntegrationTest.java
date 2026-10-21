package com.echo.service;

import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.repository.JmsTargetConnectionRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = {"echo.jms.enabled=false", "echo.request-log.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=500"})
class JmsConnectionProbeTransactionIntegrationTest extends BaseIntegrationTest {
    @Autowired JmsTargetConnectionService service;
    @Autowired JmsTargetConnectionRepository repository;
    @Autowired HikariDataSource dataSource;
    @MockitoBean(name = "tibcoFactoryProvider") JmsTargetFactoryProvider provider;

    @Test
    void threeSlowProviderTestsLeaveTheDatabaseAvailableUntilTheyActuallyExit() throws Exception {
        var factory = mock(ConnectionFactory.class);
        var connection = mock(jakarta.jms.Connection.class);
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        List<Boolean> providerTransactions = new CopyOnWriteArrayList<>();
        when(provider.supports("tibco")).thenReturn(true);
        when(provider.create(any())).thenReturn(factory);
        when(provider.createOutbound(any())).thenReturn(factory);
        when(factory.createConnection()).thenAnswer(invocation -> {
            providerTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new JMSException("Fixture did not release the provider");
            return connection;
        });
        var profile = service.create(new JmsTargetConnectionRequest(null, "Slow independent provider",
                "tibco", "tcp://127.0.0.1:1", null, null, false, "TEST.REQUEST", 1, true, true));
        var executor = Executors.newFixedThreadPool(3);
        List<Future<JmsTargetConnectionService.ConnectionTestResult>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) calls.add(executor.submit(() -> service.test(profile.id())));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            int heldConnections = dataSource.getHikariPoolMXBean().getActiveConnections();
            boolean databaseAvailable;
            long started = System.nanoTime();
            try (Connection borrowed = dataSource.getConnection()) {
                databaseAvailable = borrowed.isValid(1);
            } catch (SQLException unavailable) { databaseAvailable = false; }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf("ISSUE99_F5 held=%d borrowSucceeded=%s borrowMs=%d providerTransactions=%s%n",
                    heldConnections, databaseAvailable, elapsedMs, providerTransactions);
            assertThat(databaseAvailable).isTrue();
            assertThat(heldConnections).isZero();
            assertThat(providerTransactions).containsExactly(false, false, false);
        } finally {
            release.countDown();
            for (var call : calls) assertThat(call.get(3, TimeUnit.SECONDS).success()).isTrue();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            repository.deleteAll();
        }
    }
}
