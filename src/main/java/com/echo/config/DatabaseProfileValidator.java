package com.echo.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies that the selected database profile and the actual DataSource agree.
 *
 * <p>The check intentionally uses JDBC metadata rather than configuration
 * properties. This means a standard {@code SPRING_DATASOURCE_URL} override is
 * checked against the selected profile as well. A missing database profile is
 * treated as the application's default H2 configuration.</p>
 *
 * <p>No connection details are included in validation messages. Connection
 * failures are converted to a generic startup failure without retaining the
 * driver's exception as a cause, because JDBC driver messages can contain a
 * password embedded in a URL. The failure is still propagated and therefore
 * prevents the application from starting with an unusable database.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class DatabaseProfileValidator implements ApplicationRunner {

    private final Environment environment;
    private final DataSource dataSource;

    public DatabaseProfileValidator(Environment environment, DataSource dataSource) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void run(ApplicationArguments args) {
        DatabaseProfile selectedProfile = selectProfile(Arrays.asList(environment.getActiveProfiles()));
        validateDataSource(selectedProfile);
    }

    /**
     * Selects the configured database family. Non-database profiles such as
     * {@code dev} and {@code test} are deliberately ignored.
     *
     * @param activeProfiles Spring's active profiles
     * @return the sole database profile, or H2 when none is active
     * @throws DatabaseProfileValidationException when more than one database
     *                                            profile is active
     */
    public static DatabaseProfile selectProfile(Collection<String> activeProfiles) {
        Set<DatabaseProfile> selected = EnumSet.noneOf(DatabaseProfile.class);
        if (activeProfiles != null) {
            for (String activeProfile : activeProfiles) {
                DatabaseProfile.fromProfileName(activeProfile).ifPresent(selected::add);
            }
        }

        if (selected.size() > 1) {
            throw new DatabaseProfileValidationException(
                    "Only one database profile may be active at a time.");
        }
        return selected.isEmpty() ? DatabaseProfile.H2 : selected.iterator().next();
    }

    /**
     * Checks both JDBC URL scheme and database product name without exposing
     * either value in the exception message.
     */
    public static void validateMetadata(DatabaseProfile expectedProfile,
                                        String jdbcUrl,
                                        String databaseProductName) {
        Objects.requireNonNull(expectedProfile, "expectedProfile");

        if (!expectedProfile.matchesUrl(jdbcUrl)
                || !expectedProfile.matchesProduct(databaseProductName)) {
            throw new DatabaseProfileValidationException(
                    "Database profile '" + expectedProfile.profileName()
                            + "' does not match the configured DataSource.");
        }
    }

    private void validateDataSource(DatabaseProfile expectedProfile) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            validateMetadata(expectedProfile, metadata.getURL(), metadata.getDatabaseProductName());
        } catch (DatabaseProfileValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Do not retain the driver exception: its message may contain a
            // password from the JDBC URL. The startup failure is intentional.
            throw new DatabaseProfileValidationException(
                    "Database connection validation failed for profile '"
                            + expectedProfile.profileName() + "'.");
        }
    }

    /** Supported database profile names and their JDBC metadata fingerprints. */
    public enum DatabaseProfile {
        H2("h2", "jdbc:h2:"),
        SQLITE("sqlite", "jdbc:sqlite:"),
        POSTGRESQL("postgresql", "jdbc:postgresql:"),
        MYSQL("mysql", "jdbc:mysql:"),
        MARIADB("mariadb", "jdbc:mariadb:"),
        SQLSERVER("sqlserver", "jdbc:sqlserver:"),
        ORACLE("oracle", "jdbc:oracle:");

        private final String profileName;
        private final String jdbcPrefix;

        DatabaseProfile(String profileName, String jdbcPrefix) {
            this.profileName = profileName;
            this.jdbcPrefix = jdbcPrefix;
        }

        public String profileName() {
            return profileName;
        }

        private boolean matchesUrl(String jdbcUrl) {
            return jdbcUrl != null
                    && jdbcUrl.regionMatches(true, 0, jdbcPrefix, 0, jdbcPrefix.length());
        }

        private boolean matchesProduct(String productName) {
            if (productName == null) {
                return false;
            }
            String normalized = productName.toLowerCase(Locale.ROOT);
            return switch (this) {
                case H2 -> normalized.contains("h2");
                case SQLITE -> normalized.contains("sqlite");
                case POSTGRESQL -> normalized.contains("postgres");
                case MYSQL -> normalized.contains("mysql") && !normalized.contains("maria");
                case MARIADB -> normalized.contains("mariadb");
                case SQLSERVER -> normalized.contains("sql server")
                        || normalized.contains("microsoft sql");
                case ORACLE -> normalized.contains("oracle");
            };
        }

        private static java.util.Optional<DatabaseProfile> fromProfileName(String profileName) {
            if (profileName == null) {
                return java.util.Optional.empty();
            }
            String normalized = profileName.trim().toLowerCase(Locale.ROOT);
            for (DatabaseProfile profile : values()) {
                if (profile.profileName.equals(normalized)) {
                    return java.util.Optional.of(profile);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** A safe, actionable startup validation failure with no connection details. */
    public static final class DatabaseProfileValidationException extends IllegalStateException {

        private DatabaseProfileValidationException(String message) {
            super(message);
        }
    }
}
