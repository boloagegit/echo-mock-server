package com.echo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import static com.echo.config.DatabaseProfileValidator.DatabaseProfile.H2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseProfileValidatorTest {

    @Test
    void noDatabaseProfileUsesDefaultH2AndAllowsOtherProfiles() {
        assertThat(DatabaseProfileValidator.selectProfile(List.of("dev", "test"))).isEqualTo(H2);
        assertThat(DatabaseProfileValidator.selectProfile(List.of())).isEqualTo(H2);
        assertThat(DatabaseProfileValidator.selectProfile(null)).isEqualTo(H2);
    }

    @Test
    void duplicateDatabaseProfileIsAllowedButDifferentDatabaseProfilesAreRejected() {
        assertThat(DatabaseProfileValidator.selectProfile(List.of("dev", "oracle", "oracle")))
                .isEqualTo(DatabaseProfileValidator.DatabaseProfile.ORACLE);

        assertThatThrownBy(() -> DatabaseProfileValidator.selectProfile(
                List.of("dev", "test", "oracle", "postgresql")))
                .isInstanceOf(DatabaseProfileValidator.DatabaseProfileValidationException.class)
                .hasMessage("Only one database profile may be active at a time.");
    }

    @ParameterizedTest
    @CsvSource({
            "h2, jdbc:h2:file:./mockdb, H2",
            "sqlite, jdbc:sqlite:./mockdb.sqlite, SQLite",
            "postgresql, jdbc:postgresql://db:5432/echo, PostgreSQL",
            "mysql, jdbc:mysql://db:3306/echo, MySQL",
            "mariadb, jdbc:mariadb://db:3306/echo, MariaDB",
            "sqlserver, jdbc:sqlserver://db:1433;databaseName=echo, Microsoft SQL Server",
            "oracle, jdbc:oracle:thin:@//db:1521/echo, Oracle"
    })
    void acceptsMatchingJdbcMetadata(String profileName, String jdbcUrl, String productName) {
        var profile = DatabaseProfileValidator.selectProfile(List.of(profileName));

        DatabaseProfileValidator.validateMetadata(profile, jdbcUrl, productName);
    }

    @Test
    void rejectsDatasourceThatDoesNotMatchSelectedProfileWithoutExposingUrl() {
        String urlWithPassword = "jdbc:oracle:thin:echo/secret-password@db:1521/echo";

        assertThatThrownBy(() -> DatabaseProfileValidator.validateMetadata(
                DatabaseProfileValidator.DatabaseProfile.POSTGRESQL,
                urlWithPassword,
                "Oracle"))
                .isInstanceOf(DatabaseProfileValidator.DatabaseProfileValidationException.class)
                .hasMessage("Database profile 'postgresql' does not match the configured DataSource.")
                .hasMessageNotContaining(urlWithPassword)
                .hasMessageNotContaining("secret-password");
    }

    @Test
    void runnerChecksActualMetadataAfterStandardDatasourceOverride() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/echo");
        environment.setActiveProfiles("postgresql");
        DataSource dataSource = dataSource("jdbc:postgresql://db:5432/echo", "PostgreSQL");

        new DatabaseProfileValidator(environment, dataSource).run(null);
    }

    @Test
    void unavailableConnectionFailsStartupWithoutLeakingDriverDetails() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("oracle");
        DataSource dataSource = mock(DataSource.class);
        String secret = "jdbc:oracle:thin:echo/secret-password@db:1521/echo";
        when(dataSource.getConnection()).thenThrow(new SQLException("Cannot connect to " + secret));

        assertThatThrownBy(() -> new DatabaseProfileValidator(environment, dataSource).run(null))
                .isInstanceOf(DatabaseProfileValidator.DatabaseProfileValidationException.class)
                .hasMessage("Database connection validation failed for profile 'oracle'.")
                .hasNoCause()
                .hasMessageNotContaining(secret)
                .hasMessageNotContaining("secret-password");
    }

    @Test
    void multipleDatabaseProfilesAreRejectedBeforeConnectionAttempt() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("h2", "sqlite");
        DataSource dataSource = mock(DataSource.class);

        assertThatThrownBy(() -> new DatabaseProfileValidator(environment, dataSource).run(null))
                .isInstanceOf(DatabaseProfileValidator.DatabaseProfileValidationException.class)
                .hasMessage("Only one database profile may be active at a time.");

        verifyNoInteractions(dataSource);
    }

    private static DataSource dataSource(String url, String productName) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(url);
        when(metadata.getDatabaseProductName()).thenReturn(productName);
        return dataSource;
    }
}
