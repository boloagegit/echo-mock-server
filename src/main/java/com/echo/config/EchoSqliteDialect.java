package com.echo.config;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;

import java.sql.SQLException;

/**
 * Hibernate 6.6's community {@link SQLiteDialect} does not translate {@code SQLITE_CONSTRAINT} into
 * {@link ConstraintViolationException}, so Spring throws only {@code JpaSystemException};
 * code handling {@code DataIntegrityViolationException}, such as concurrent creation of same-named scenarios, cannot handle it correctly.
 * <p>
 * The Xerial driver returns an extended error code (for example, UNIQUE is 2067), and the lower 8 bits are the main error code 19.
 */
public class EchoSqliteDialect extends SQLiteDialect {

    /** SQLITE_CONSTRAINT: UNIQUE, NOT NULL, CHECK, FOREIGN KEY, PRIMARY KEY all belong to this category. */
    static final int SQLITE_CONSTRAINT = 19;

    public EchoSqliteDialect() {
        super();
    }

    public EchoSqliteDialect(DialectResolutionInfo info) {
        super(info);
    }

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        SQLExceptionConversionDelegate base = super.buildSQLExceptionConversionDelegate();
        return (sqlException, message, sql) -> {
            if (isConstraintViolation(sqlException)) {
                return new ConstraintViolationException(message, sqlException, sql, null);
            }
            return base == null ? null : base.convert(sqlException, message, sql);
        };
    }

    static boolean isConstraintViolation(SQLException exception) {
        return (exception.getErrorCode() & 0xFF) == SQLITE_CONSTRAINT;
    }
}
