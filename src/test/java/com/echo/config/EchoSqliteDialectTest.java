package com.echo.config;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class EchoSqliteDialectTest {

    @Test
    void recognisesPrimaryAndExtendedConstraintCodes() {
        assertThat(EchoSqliteDialect.isConstraintViolation(new SQLException("x", "", 19))).isTrue();
        assertThat(EchoSqliteDialect.isConstraintViolation(new SQLException("unique", "", 2067))).isTrue();
        assertThat(EchoSqliteDialect.isConstraintViolation(new SQLException("foreign key", "", 787))).isTrue();
        assertThat(EchoSqliteDialect.isConstraintViolation(new SQLException("busy", "", 5))).isFalse();
        assertThat(EchoSqliteDialect.isConstraintViolation(new SQLException("locked", "", 6))).isFalse();
    }
}
