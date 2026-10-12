package com.echo.config;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;

import java.sql.SQLException;

/**
 * Hibernate 6.6 的社群版 {@link SQLiteDialect} 沒有把 {@code SQLITE_CONSTRAINT} 轉成
 * {@link ConstraintViolationException}，Spring 因此只會拋出 {@code JpaSystemException}，
 * 依賴 {@code DataIntegrityViolationException} 的程式（例如並行建立同名 Scenario）無法正確處理。
 * <p>
 * Xerial 驅動回傳延伸錯誤碼（例如 UNIQUE 為 2067），低 8 位元才是主錯誤碼 19。
 */
public class EchoSqliteDialect extends SQLiteDialect {

    /** SQLITE_CONSTRAINT：UNIQUE、NOT NULL、CHECK、FOREIGN KEY、PRIMARY KEY 都屬於這一類。 */
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
