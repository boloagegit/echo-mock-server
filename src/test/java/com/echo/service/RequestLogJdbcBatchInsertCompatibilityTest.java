package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.PostgreSQLDialect;
import com.echo.config.EchoSqliteDialect;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.MappingMetamodelImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.type.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestLogJdbcBatchInsertCompatibilityTest {
    @Test
    void unsupportedCompositeMappingFallsBackToJpa() {
        var entityManagers = mock(EntityManagerFactory.class);
        var sessions = mock(SessionFactoryImplementor.class);
        var jdbc = mock(JdbcServices.class);
        var mappings = mock(MappingMetamodelImplementor.class);
        var descriptor = mock(AbstractEntityPersister.class);
        when(entityManagers.unwrap(SessionFactoryImplementor.class)).thenReturn(sessions);
        when(sessions.getJdbcServices()).thenReturn(jdbc);
        when(jdbc.getDialect()).thenReturn(new EchoSqliteDialect());
        when(sessions.getMappingMetamodel()).thenReturn(mappings);
        when(mappings.getEntityDescriptor(RequestLog.class)).thenReturn(descriptor);
        when(descriptor.getPropertyTypes()).thenReturn(new Type[]{mock(Type.class)});
        when(descriptor.getPropertyInsertability()).thenReturn(new boolean[]{true});
        when(descriptor.getPropertyColumnNames(0)).thenReturn(new String[]{"first", "second"});

        var inserts = new RequestLogJdbcBatchInsert(entityManagers);
        assertThat(inserts.insertNew(List.of(RequestLog.builder().protocol(Protocol.HTTP).endpoint("/").build())))
                .isFalse();
    }

    @Test
    void otherDatabasesDeclineJdbcPathWithoutAccessingSqliteMappingOrConnection() {
        var entityManagers = mock(EntityManagerFactory.class);
        var sessions = mock(SessionFactoryImplementor.class);
        var jdbc = mock(JdbcServices.class);
        when(entityManagers.unwrap(SessionFactoryImplementor.class)).thenReturn(sessions);
        when(sessions.getJdbcServices()).thenReturn(jdbc);
        when(jdbc.getDialect()).thenReturn(new PostgreSQLDialect());
        var inserts = new RequestLogJdbcBatchInsert(entityManagers);

        assertThat(inserts.insertNew(List.of(RequestLog.builder().protocol(Protocol.HTTP).endpoint("/").build())))
                .isFalse();
        assertThat(inserts.insertNew(List.of())).isFalse();
        verify(sessions, never()).getMappingMetamodel();
    }
}
