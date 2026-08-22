package com.echo.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies application tables from an offline H2 database into an already-created SQLite schema.
 *
 * <p>The target is expected to be a disposable staging database. All table writes happen in one
 * transaction, and every migrated value is normalized according to the SQLite column type and
 * verified with a deterministic SHA-256 digest before commit.</p>
 */
public final class H2ToSqliteMigrator {

    public static final List<String> APPLICATION_TABLES = List.of(
            "responses",
            "http_rules",
            "jms_rules",
            "builtin_users",
            "rule_audit_logs",
            "request_log",
            "request_log_checkpoint",
            "cache_events",
            "issue_reports",
            "scenarios",
            "jms_target_connections",
            "http_target_connections"
    );

    private static final Map<String, Set<String>> ALLOWED_LEGACY_COLUMNS = Map.of(
            "request_log", Set.of("condition_matched")
    );
    // Older Echo databases legitimately predate these feature tables. Missing means zero rows.
    private static final Set<String> OPTIONAL_SOURCE_TABLES = Set.of(
            "jms_target_connections", "http_target_connections", "request_log_checkpoint",
            "scenarios");
    // An older H2 database may predate matched-rule forwarding. Null preserves old MOCK behavior.
    private static final Map<String, Set<String>> ALLOWED_TARGET_ONLY_COLUMNS = Map.of(
            "http_rules", Set.of("action", "forward_target_mode", "http_target_connection_id"),
            "jms_rules", Set.of("action", "forward_target_mode", "jms_target_connection_id")
    );

    private H2ToSqliteMigrator() {
    }

    /** Command-line entry point used by the migration script through Gradle. */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String h2Url = required(env, "ECHO_MIGRATION_H2_URL");
        String sqliteUrl = required(env, "ECHO_MIGRATION_SQLITE_URL");
        String h2User = env.getOrDefault("ECHO_MIGRATION_H2_USER", "sa");
        String h2Password = env.getOrDefault("ECHO_MIGRATION_H2_PASSWORD", "");
        Path reportPath = Path.of(required(env, "ECHO_MIGRATION_REPORT"));

        MigrationReport report = migrate(h2Url, h2User, h2Password, sqliteUrl, APPLICATION_TABLES);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(reportPath.toFile(), report);

        System.out.printf(Locale.ROOT, "Migrated and verified %,d rows across %d tables.%n",
                report.totalRows(), report.tables().size());
    }

    public static MigrationReport migrate(String h2Url, String h2User, String h2Password,
                                           String sqliteUrl, List<String> tables) throws SQLException {
        try (Connection source = DriverManager.getConnection(h2Url, h2User, h2Password);
             Connection target = DriverManager.getConnection(sqliteUrl)) {
            source.setReadOnly(true);
            configureTarget(target);
            target.setAutoCommit(false);

            try {
                List<TablePlan> plans = new ArrayList<>();
                for (String table : tables) {
                    plans.add(planTable(source, target, table));
                }

                deleteTargetRows(target, plans);

                List<TableReport> reports = new ArrayList<>();
                long totalRows = 0;
                for (TablePlan plan : plans) {
                    TableReport report = copyAndVerifyTable(source, target, plan);
                    reports.add(report);
                    totalRows += report.sourceRows();
                }

                verifyDatabase(target);
                target.commit();
                return new MigrationReport(List.copyOf(reports), totalRows, "ok", "ok");
            } catch (Exception e) {
                target.rollback();
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Migration failed", e);
            }
        }
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static void configureTarget(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA foreign_keys=OFF");
        }
    }

    private static TablePlan planTable(Connection source, Connection target, String rawTable)
            throws SQLException {
        String table = normalizeName(rawTable);
        List<String> sourceColumns = sourceColumns(source, table);
        List<TargetColumn> targetColumns = targetColumns(target, table);

        if (targetColumns.isEmpty()) {
            throw new SQLException("Required SQLite table is missing: " + table);
        }

        List<String> primaryKeys = targetColumns.stream()
                .filter(column -> column.primaryKeyPosition() > 0)
                .sorted((left, right) -> Integer.compare(
                        left.primaryKeyPosition(), right.primaryKeyPosition()))
                .map(TargetColumn::name)
                .toList();
        if (primaryKeys.isEmpty()) {
            throw new SQLException("SQLite table has no primary key: " + table);
        }

        if (sourceColumns.isEmpty()) {
            if (!OPTIONAL_SOURCE_TABLES.contains(table)) {
                throw new SQLException("Required H2 table is missing: " + table);
            }
            List<ColumnPlan> emptySourceColumns = targetColumns.stream()
                    .map(column -> new ColumnPlan(column.name(), column))
                    .toList();
            return new TablePlan(table, emptySourceColumns, primaryKeys, List.of(), true);
        }

        Map<String, String> sourceByNormalizedName = new LinkedHashMap<>();
        for (String sourceColumn : sourceColumns) {
            sourceByNormalizedName.put(normalizeName(sourceColumn), sourceColumn);
        }

        List<ColumnPlan> columns = new ArrayList<>();
        Set<String> allowedTargetOnly = ALLOWED_TARGET_ONLY_COLUMNS.getOrDefault(table, Set.of());
        for (TargetColumn targetColumn : targetColumns) {
            String sourceColumn = sourceByNormalizedName.remove(targetColumn.name());
            if (sourceColumn == null) {
                if (!allowedTargetOnly.contains(targetColumn.name())) {
                    throw new SQLException("SQLite column has no H2 source: " + table + "."
                            + targetColumn.name());
                }
                columns.add(new ColumnPlan(null, targetColumn));
                continue;
            }
            columns.add(new ColumnPlan(sourceColumn, targetColumn));
        }

        Set<String> allowedLegacy = ALLOWED_LEGACY_COLUMNS.getOrDefault(table, Set.of());
        Set<String> unexpected = new HashSet<>(sourceByNormalizedName.keySet());
        unexpected.removeAll(allowedLegacy);
        if (!unexpected.isEmpty()) {
            throw new SQLException("Unexpected H2-only columns in " + table + ": " + unexpected);
        }

        return new TablePlan(table, List.copyOf(columns), primaryKeys,
                sourceByNormalizedName.keySet().stream().sorted().toList(), false);
    }

    private static List<String> sourceColumns(Connection source, String table) throws SQLException {
        String sql = "SELECT * FROM " + quoteH2(table) + " WHERE 1 = 0";
        try (Statement statement = source.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            List<String> columns = new ArrayList<>(metadata.getColumnCount());
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                columns.add(metadata.getColumnLabel(index));
            }
            return columns;
        } catch (SQLException e) {
            if (isMissingTable(source, table)) {
                return List.of();
            }
            throw e;
        }
    }

    private static boolean isMissingTable(Connection source, String table) throws SQLException {
        DatabaseMetaData metadata = source.getMetaData();
        try (ResultSet tables = metadata.getTables(null, null, table.toUpperCase(Locale.ROOT),
                new String[]{"TABLE"})) {
            return !tables.next();
        }
    }

    private static List<TargetColumn> targetColumns(Connection target, String table) throws SQLException {
        List<TargetColumn> columns = new ArrayList<>();
        try (Statement statement = target.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + quoteSqlite(table) + ")")) {
            while (resultSet.next()) {
                columns.add(new TargetColumn(
                        normalizeName(resultSet.getString("name")),
                        resultSet.getString("type"),
                        resultSet.getInt("pk")
                ));
            }
        }
        return columns;
    }

    private static void deleteTargetRows(Connection target, List<TablePlan> plans) throws SQLException {
        try (Statement statement = target.createStatement()) {
            for (int index = plans.size() - 1; index >= 0; index--) {
                statement.executeUpdate("DELETE FROM " + quoteSqlite(plans.get(index).table()));
            }
        }
    }

    private static TableReport copyAndVerifyTable(Connection source, Connection target,
                                                   TablePlan plan) throws SQLException {
        if (plan.sourceMissing()) {
            return verifyMissingOptionalSource(target, plan);
        }
        String sourceSql = selectSql(plan, true);
        String targetSql = selectSql(plan, false);
        String insertSql = insertSql(plan);
        MessageDigest sourceDigest = sha256();
        long sourceRows = 0;

        digestMetadata(sourceDigest, plan);
        try (Statement sourceStatement = source.createStatement();
             ResultSet sourceRowsResult = sourceStatement.executeQuery(sourceSql);
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            while (sourceRowsResult.next()) {
                sourceRows++;
                digestLong(sourceDigest, sourceRows);
                for (int index = 0; index < plan.columns().size(); index++) {
                    ColumnPlan column = plan.columns().get(index);
                    CellValue value = readSourceValue(sourceRowsResult, index + 1, column.target());
                    value.bind(insert, index + 1);
                    digestCell(sourceDigest, value.canonicalBytes());
                }
                insert.addBatch();
                if (sourceRows % 500 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }

        MessageDigest targetDigest = sha256();
        long targetRows = 0;
        digestMetadata(targetDigest, plan);
        try (Statement targetStatement = target.createStatement();
             ResultSet targetRowsResult = targetStatement.executeQuery(targetSql)) {
            while (targetRowsResult.next()) {
                targetRows++;
                digestLong(targetDigest, targetRows);
                for (int index = 0; index < plan.columns().size(); index++) {
                    TargetColumn column = plan.columns().get(index).target();
                    CellValue value = readTargetValue(targetRowsResult, index + 1, column);
                    digestCell(targetDigest, value.canonicalBytes());
                }
            }
        }

        String sourceHash = hex(sourceDigest.digest());
        String targetHash = hex(targetDigest.digest());
        if (sourceRows != targetRows) {
            throw new SQLException("Row-count mismatch for " + plan.table() + ": H2="
                    + sourceRows + ", SQLite=" + targetRows);
        }
        if (!sourceHash.equals(targetHash)) {
            throw new SQLException("Content hash mismatch for " + plan.table() + ": H2="
                    + sourceHash + ", SQLite=" + targetHash);
        }

        return new TableReport(plan.table(), sourceRows, targetRows, sourceHash, targetHash,
                plan.skippedLegacyColumns());
    }

    private static TableReport verifyMissingOptionalSource(Connection target, TablePlan plan)
            throws SQLException {
        long targetRows;
        try (Statement statement = target.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + quoteSqlite(plan.table()))) {
            result.next();
            targetRows = result.getLong(1);
        }
        if (targetRows != 0) {
            throw new SQLException("Row-count mismatch for absent optional table "
                    + plan.table() + ": H2=0, SQLite=" + targetRows);
        }
        MessageDigest digest = sha256();
        digestMetadata(digest, plan);
        String hash = hex(digest.digest());
        return new TableReport(plan.table(), 0, 0, hash, hash, List.of());
    }

    private static String selectSql(TablePlan plan, boolean h2) {
        String columns = plan.columns().stream()
                .map(column -> h2 ? (column.sourceName() == null ? "NULL" : quoteH2(column.sourceName()))
                        : quoteSqlite(column.target().name()))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String order = plan.primaryKeys().stream()
                .map(key -> h2 ? quoteH2(key) : quoteSqlite(key))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        return "SELECT " + columns + " FROM "
                + (h2 ? quoteH2(plan.table()) : quoteSqlite(plan.table()))
                + " ORDER BY " + order;
    }

    private static String insertSql(TablePlan plan) {
        String columns = plan.columns().stream()
                .map(column -> quoteSqlite(column.target().name()))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String placeholders = String.join(", ", java.util.Collections.nCopies(
                plan.columns().size(), "?"));
        return "INSERT INTO " + quoteSqlite(plan.table()) + " (" + columns + ") VALUES ("
                + placeholders + ")";
    }

    private static CellValue readSourceValue(ResultSet rows, int index, TargetColumn target)
            throws SQLException {
        Object raw = rows.getObject(index);
        if (raw == null) {
            return CellValue.nullValue();
        }
        if (raw instanceof Clob clob) {
            raw = clob.getSubString(1, Math.toIntExact(clob.length()));
        } else if (raw instanceof Blob blob) {
            raw = blob.getBytes(1, Math.toIntExact(blob.length()));
        }
        return normalizeValue(raw, target, true);
    }

    private static CellValue readTargetValue(ResultSet rows, int index, TargetColumn target)
            throws SQLException {
        Object raw = rows.getObject(index);
        if (raw == null) {
            return CellValue.nullValue();
        }
        return normalizeValue(raw, target, false);
    }

    private static CellValue normalizeValue(Object raw, TargetColumn target, boolean source)
            throws SQLException {
        String type = target.type().toUpperCase(Locale.ROOT);
        if (type.contains("BOOL")) {
            long value;
            if (raw instanceof Boolean booleanValue) {
                value = booleanValue ? 1 : 0;
            } else if (raw instanceof Number number) {
                value = number.longValue() == 0 ? 0 : 1;
            } else {
                String stringValue = raw.toString();
                if ("TRUE".equalsIgnoreCase(stringValue) || "1".equals(stringValue)) {
                    value = 1;
                } else if ("FALSE".equalsIgnoreCase(stringValue) || "0".equals(stringValue)) {
                    value = 0;
                } else {
                    throw new SQLException("Invalid boolean value for " + target.name() + ": " + raw);
                }
            }
            return CellValue.integer(value);
        }
        if (isTemporal(type)) {
            long epochMillis;
            if (raw instanceof Timestamp timestamp) {
                epochMillis = timestamp.getTime();
            } else if (raw instanceof Number number) {
                epochMillis = number.longValue();
            } else if (source) {
                epochMillis = Timestamp.valueOf(raw.toString()).getTime();
            } else {
                throw new SQLException("Unexpected SQLite timestamp representation for "
                        + target.name() + ": " + raw.getClass().getName());
            }
            return CellValue.timestamp(epochMillis);
        }
        if (type.contains("INT")) {
            if (raw instanceof Number number) {
                return CellValue.integer(number.longValue());
            }
            try {
                return CellValue.integer(Long.parseLong(raw.toString()));
            } catch (NumberFormatException e) {
                throw new SQLException("Invalid integer value for " + target.name() + ": " + raw, e);
            }
        }
        if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")
                || type.contains("DECIMAL") || type.contains("NUMERIC")) {
            try {
                BigDecimal decimal = raw instanceof BigDecimal bigDecimal
                        ? bigDecimal : new BigDecimal(raw.toString());
                return CellValue.decimal(decimal.stripTrailingZeros().toPlainString());
            } catch (NumberFormatException e) {
                throw new SQLException("Invalid decimal value for " + target.name() + ": " + raw, e);
            }
        }
        if (type.contains("BLOB") || raw instanceof byte[]) {
            if (!(raw instanceof byte[] bytes)) {
                throw new SQLException("Invalid binary value for " + target.name());
            }
            return CellValue.binary(bytes);
        }
        return CellValue.text(raw.toString());
    }

    private static boolean isTemporal(String type) {
        return type.contains("DATE") || type.contains("TIME");
    }

    private static void verifyDatabase(Connection target) throws SQLException {
        try (Statement statement = target.createStatement();
             ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
            if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                throw new SQLException("SQLite integrity_check failed");
            }
        }
        try (Statement statement = target.createStatement();
             ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (foreignKeys.next()) {
                throw new SQLException("SQLite foreign_key_check failed for table "
                        + foreignKeys.getString(1));
            }
        }
    }

    private static void digestMetadata(MessageDigest digest, TablePlan plan) {
        digestCell(digest, plan.table().getBytes(StandardCharsets.UTF_8));
        for (ColumnPlan column : plan.columns()) {
            digestCell(digest, column.target().name().getBytes(StandardCharsets.UTF_8));
            digestCell(digest, column.target().type().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void digestLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void digestCell(MessageDigest digest, byte[] value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
        } else {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            digest.update(value);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String quoteH2(String identifier) {
        return '"' + identifier.toUpperCase(Locale.ROOT).replace("\"", "\"\"") + '"';
    }

    private static String quoteSqlite(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    public record MigrationReport(List<TableReport> tables, long totalRows,
                                  String integrityCheck, String foreignKeyCheck) {
        public MigrationReport {
            tables = List.copyOf(tables);
        }

        @Override
        public List<TableReport> tables() {
            return List.copyOf(tables);
        }
    }

    public record TableReport(String table, long sourceRows, long targetRows,
                              String sourceSha256, String targetSha256,
                              List<String> skippedLegacyColumns) {
        public TableReport {
            skippedLegacyColumns = List.copyOf(skippedLegacyColumns);
        }

        @Override
        public List<String> skippedLegacyColumns() {
            return List.copyOf(skippedLegacyColumns);
        }
    }

    private record TablePlan(String table, List<ColumnPlan> columns, List<String> primaryKeys,
                             List<String> skippedLegacyColumns, boolean sourceMissing) {
    }

    private record ColumnPlan(String sourceName, TargetColumn target) {
    }

    private record TargetColumn(String name, String type, int primaryKeyPosition) {
        private TargetColumn {
            type = type == null ? "" : type;
        }
    }

    private static final class CellValue {
        private final Object bindValue;
        private final byte[] canonicalBytes;
        private final ValueKind kind;

        private CellValue(Object bindValue, byte[] canonicalBytes, ValueKind kind) {
            this.bindValue = bindValue;
            this.canonicalBytes = canonicalBytes == null ? null : canonicalBytes.clone();
            this.kind = kind;
        }

        static CellValue nullValue() {
            return new CellValue(null, null, ValueKind.NULL);
        }

        static CellValue integer(long value) {
            return new CellValue(value, Long.toString(value).getBytes(StandardCharsets.UTF_8),
                    ValueKind.INTEGER);
        }

        static CellValue timestamp(long epochMillis) {
            return new CellValue(new Timestamp(epochMillis),
                    Long.toString(epochMillis).getBytes(StandardCharsets.UTF_8), ValueKind.TIMESTAMP);
        }

        static CellValue decimal(String value) {
            return new CellValue(value, value.getBytes(StandardCharsets.UTF_8), ValueKind.DECIMAL);
        }

        static CellValue binary(byte[] value) {
            return new CellValue(value, Base64.getEncoder().encode(value), ValueKind.BINARY);
        }

        static CellValue text(String value) {
            return new CellValue(value, value.getBytes(StandardCharsets.UTF_8), ValueKind.TEXT);
        }

        byte[] canonicalBytes() {
            return canonicalBytes == null ? null : canonicalBytes.clone();
        }

        void bind(PreparedStatement statement, int index) throws SQLException {
            switch (kind) {
                case NULL -> statement.setObject(index, null);
                case INTEGER -> statement.setLong(index, (Long) bindValue);
                case TIMESTAMP -> statement.setTimestamp(index, (Timestamp) bindValue);
                case DECIMAL, TEXT -> statement.setString(index, (String) bindValue);
                case BINARY -> statement.setBytes(index, (byte[]) bindValue);
            }
        }
    }

    private enum ValueKind {
        NULL, INTEGER, TIMESTAMP, DECIMAL, BINARY, TEXT
    }
}
