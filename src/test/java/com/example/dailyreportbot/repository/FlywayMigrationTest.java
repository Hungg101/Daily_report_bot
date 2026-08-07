package com.example.dailyreportbot.repository;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flyway-schema-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void shouldMigrateEmptyDatabaseAndRecordV1ThroughV11() {
        MigrationInfo current = flyway.info().current();

        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("11");
        assertThat(current.getDescription()).isEqualTo("add daily report performer");
        assertThat(current.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(tableNames()).contains(
                "users",
                "daily_reports",
                "identity_admin_audit_events",
                "reminder_occurrences",
                "reminder_delivery_attempts",
                "flyway_schema_history"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'collaborators'
                """,
                Integer.class
        )).isEqualTo(500);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'collaborators'
                """,
                String.class
        )).isEqualTo("YES");
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'performer'
                """
        )).containsEntry("character_maximum_length", 255L)
                .containsEntry("is_nullable", "YES");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND column_name IN ('admin', 'manager')
                  AND UPPER(data_type) = 'BOOLEAN'
                  AND is_nullable = 'NO'
                  AND column_default IS NOT NULL
                """,
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name IN ('department', 'unit')
                  AND character_maximum_length = 255
                  AND is_nullable = 'YES'
                """,
                Integer.class
        )).isEqualTo(2);

        long defaultedUserId = insertUser(8001L, null, null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT admin FROM users WHERE id = ?",
                Boolean.class,
                defaultedUserId
        )).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT manager FROM users WHERE id = ?",
                Boolean.class,
                defaultedUserId
        )).isFalse();
    }

    @Test
    void shouldAcceptOnlyKnownManagementAuditActions() {
        long userId = insertUser(8002L, null, null);

        for (String action : List.of(
                "UPDATE_REPORT",
                "DELETE_REPORT",
                "GRANT_MANAGER",
                "REVOKE_MANAGER",
                "GRANT_ADMIN",
                "REVOKE_ADMIN"
        )) {
            assertThat(jdbcTemplate.update(
                    """
                    INSERT INTO identity_admin_audit_events (
                        action, actor, target_user_id, reason, before_state, after_state, created_at
                    ) VALUES (?, 'user:1', ?, 'approved', '{}', '{}', CURRENT_TIMESTAMP)
                    """,
                    action,
                    userId
            )).isEqualTo(1);
        }

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('UNKNOWN_MANAGEMENT_ACTION', 'user:1', ?, 'approved', '{}', '{}', CURRENT_TIMESTAMP)
                """,
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldValidateHibernateMappingsAgainstMigratedSchema() {
        Set<String> entityNames = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(entityNames).contains("User", "DailyReport", "IdentityAuditEvent");
        assertThat(entityManagerFactory.isOpen()).isTrue();
    }

    @Test
    void shouldExposeNamedConstraintsAndReportLookupIndex() throws SQLException {
        assertThat(constraintNames("users")).contains(
                "pk_users",
                "uk_users_telegram_user_id",
                "uk_users_internal_id",
                "uk_users_employee_code",
                "ck_users_phone_number_not_blank",
                "ck_users_employee_code_normalized",
                "ck_users_employee_code_not_blank",
                "ck_users_status"
        );
        assertThat(constraintNames("daily_reports")).contains(
                "pk_daily_reports",
                "fk_daily_reports_user",
                "ck_daily_reports_content_not_blank"
        );
        assertThat(constraintNames("identity_admin_audit_events")).contains(
                "pk_identity_admin_audit",
                "fk_identity_admin_audit_target_user",
                "fk_identity_admin_audit_related_user",
                "ck_identity_admin_audit_action",
                "ck_identity_admin_audit_actor_not_blank",
                "ck_identity_admin_audit_reason_not_blank",
                "ck_identity_admin_audit_actor_has_non_whitespace",
                "ck_identity_admin_audit_reason_has_non_whitespace"
        );

        List<String> indexedColumns = indexColumns("daily_reports", "idx_daily_reports_user_date_created");
        assertThat(indexedColumns).containsExactly("user_id:A", "report_date:A", "created_at:D");
        assertThat(indexColumns("identity_admin_audit_events", "idx_identity_admin_audit_target_created"))
                .containsExactly("target_user_id:A", "created_at:D");
    }

    @Test
    void shouldNormalizeExistingEmployeeCodesWhenApplyingV2() {
        String url = "jdbc:h2:mem:flyway-v2-normalization;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(migrationDataSource).target("1").load().migrate();
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
        migrationJdbc.update(
                "INSERT INTO users (employee_code, status, created_at, updated_at) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                " emp-01 "
        );

        Flyway.configure().dataSource(migrationDataSource).target("2").load().migrate();

        assertThat(migrationJdbc.queryForObject("SELECT employee_code FROM users", String.class))
                .isEqualTo("EMP-01");
    }

    @Test
    void shouldEnforceNormalizedEmployeeCodesSupportedAuditActionsAndNonblankContext() {
        assertThatThrownBy(() -> insertUser(4001L, " emp004 ", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        long userId = insertUser(4002L, "EMP004", null);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('DELETE', 'internal', ?, 'invalid action', '{}', '{}', CURRENT_TIMESTAMP)
                """,
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', '   ', ?, 'valid reason', '{}', '{}', CURRENT_TIMESTAMP)
                """,
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', 'valid actor', ?, '   ', '{}', '{}', CURRENT_TIMESTAMP)
                """,
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', ?, ?, 'valid reason', '{}', '{}', CURRENT_TIMESTAMP)
                """,
                "\t",
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', 'valid actor', ?, ?, '{}', '{}', CURRENT_TIMESTAMP)
                """,
                userId,
                "\n"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceNonWhitespaceReportContentAndEmployeeCodesAtDatabaseBoundary() {
        long userId = insertUser(4101L, null, null);

        assertThatThrownBy(() -> insertReport(userId, ""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReport(userId, " \t\r\n "))
                .isInstanceOf(DataIntegrityViolationException.class);

        long reportId = insertReport(userId, " valid report ");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE daily_reports SET content = ? WHERE id = ?",
                "\n",
                reportId
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertUser(4102L, "", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(4103L, "\t", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET employee_code = ? WHERE id = ?",
                " \r\n ",
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);

        long validEmployeeUserId = insertUser(4104L, "EMP-VALID", null);
        assertThat(validEmployeeUserId).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT employee_code FROM users WHERE id = ?",
                String.class,
                validEmployeeUserId
        )).isEqualTo("EMP-VALID");
    }

    @Test
    void shouldEnforceUniqueNonNullUserIdentifiers() {
        insertUser(1001L, "EMP001", "+841001");

        assertThatThrownBy(() -> insertUser(1001L, "EMP002", "+841002"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(1002L, "EMP001", "+841003"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(1003L, "EMP003", "+841001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowRepeatedNullOptionalEmployeeCodes() {
        insertUser(2001L, null, null);
        insertUser(2002L, null, null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE telegram_user_id IN (2001, 2002)",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void shouldEnforceOwnershipAndAllowMultipleReportsPerUserDate() {
        long userId = insertUser(3001L, null, null);

        assertThatThrownBy(() -> insertReport(999999L, "Orphan"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertReport(userId, "First");
        insertReport(userId, "Second");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_reports WHERE user_id = ? AND report_date = DATE '2026-07-02'",
                Integer.class,
                userId
        )).isEqualTo(2);
    }

    @Test
    void shouldUpgradeV1DatabaseThroughV11AndNotReapplyVersionedMigrations() {
        String url = "jdbc:h2:mem:flyway-v9-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .target("1")
                .load()
                .migrate();
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
        migrationJdbc.update(
                """
                INSERT INTO users (
                    telegram_user_id, phone_number, department_name, unit_name, status, created_at, updated_at
                ) VALUES (8101, '+848101', 'Legacy Department', 'Legacy Unit', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """
        );
        Long preservedUserId = migrationJdbc.queryForObject(
                "SELECT id FROM users WHERE telegram_user_id = 8101",
                Long.class
        );
        migrationJdbc.update(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, DATE '2026-07-15', 'v1 preserved report', CURRENT_TIMESTAMP)
                """,
                preservedUserId
        );
        Flyway upgraded = Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .load();

        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(upgraded.info().current().getDescription())
                .isEqualTo("add daily report performer");
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11'",
                Integer.class
        )).isEqualTo(1);
        assertThat(migrationJdbc.queryForObject(
                "SELECT user_id FROM daily_reports WHERE content = 'v1 preserved report'",
                Long.class
        )).isEqualTo(preservedUserId);
        assertThat(migrationJdbc.queryForObject(
                "SELECT report_date FROM daily_reports WHERE content = 'v1 preserved report'",
                java.sql.Date.class
        ).toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(migrationJdbc.queryForObject(
                "SELECT admin FROM users WHERE id = ?",
                Boolean.class,
                preservedUserId
        )).isFalse();
        assertThat(migrationJdbc.queryForObject(
                "SELECT manager FROM users WHERE id = ?",
                Boolean.class,
                preservedUserId
        )).isFalse();
        assertThat(migrationJdbc.queryForObject(
                "SELECT department FROM daily_reports WHERE content = 'v1 preserved report'",
                String.class
        )).isNull();
        assertThat(migrationJdbc.queryForObject(
                "SELECT unit FROM daily_reports WHERE content = 'v1 preserved report'",
                String.class
        )).isNull();
        assertThat(migrationJdbc.queryForObject(
                "SELECT performer FROM daily_reports WHERE content = 'v1 preserved report'",
                String.class
        )).isNull();
    }

    @Test
    void shouldPreserveNumericOwnershipWhenUpgradingEligibleV6Data() {
        String url = "jdbc:h2:mem:flyway-v7-owner-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .target("6")
                .load()
                .migrate();
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
        migrationJdbc.update(
                """
                INSERT INTO users (telegram_user_id, phone_number, status, created_at, updated_at, version)
                VALUES (7001, '+84907001', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """
        );
        Long userId = migrationJdbc.queryForObject(
                "SELECT id FROM users WHERE phone_number = '+84907001'",
                Long.class
        );
        migrationJdbc.update(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, DATE '2026-07-14', 'preserved', CURRENT_TIMESTAMP)
                """,
                userId
        );

        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .load()
                .migrate();

        assertThat(constraintColumns(migrationJdbc, "users", "pk_users"))
                .containsExactly("phone_number");
        assertThat(migrationJdbc.queryForObject(
                "SELECT user_id FROM daily_reports WHERE content = 'preserved'",
                Long.class
        )).isEqualTo(userId);
    }

    @Test
    void shouldFailV7BeforeSchemaMutationWhenExistingPhoneIsMissing() {
        String url = "jdbc:h2:mem:flyway-v7-missing-phone;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .target("6")
                .load()
                .migrate();
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
        migrationJdbc.update(
                """
                INSERT INTO users (telegram_user_id, status, created_at, updated_at, version)
                VALUES (7002, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """
        );

        Flyway upgrade = Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .load();

        assertThatThrownBy(upgrade::migrate).isInstanceOf(FlywayException.class);
        assertThat(constraintColumns(migrationJdbc, "users", "pk_users")).containsExactly("id");
        assertThat(constraintNames(migrationJdbc, "users")).doesNotContain("uk_users_internal_id");
        assertThat(migrationJdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE telegram_user_id = 7002",
                Integer.class
        )).isEqualTo(1);
    }

    private long insertUser(Long telegramUserId, String employeeCode, String phoneNumber) {
        String requiredPhoneNumber = phoneNumber != null ? phoneNumber : "+84" + telegramUserId;
        jdbcTemplate.update(
                """
                INSERT INTO users (
                    telegram_user_id, employee_code, phone_number, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                telegramUserId,
                employeeCode,
                requiredPhoneNumber
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE telegram_user_id = ?",
                Long.class,
                telegramUserId
        );
    }

    private long insertReport(long userId, String content) {
        jdbcTemplate.update(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, DATE '2026-07-02', ?, CURRENT_TIMESTAMP)
                """,
                userId,
                content
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM daily_reports WHERE user_id = ? AND content = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                userId,
                content
        );
    }

    private List<String> tableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );
    }

    private List<String> constraintNames(String tableName) {
        return constraintNames(jdbcTemplate, tableName);
    }

    private List<String> constraintNames(JdbcTemplate template, String tableName) {
        return template.queryForList(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = ?
                """,
                String.class,
                tableName
        );
    }

    private List<String> constraintColumns(JdbcTemplate template, String tableName, String constraintName) {
        return template.queryForList(
                """
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = 'public' AND table_name = ? AND constraint_name = ?
                ORDER BY ordinal_position
                """,
                String.class,
                tableName,
                constraintName
        );
    }

    private List<String> indexColumns(String tableName, String indexName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet indexes = metadata.getIndexInfo(null, "public", tableName, false, false)) {
                while (indexes.next()) {
                    if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        columns.add(indexes.getString("COLUMN_NAME") + ":" + indexes.getString("ASC_OR_DESC"));
                    }
                }
            }
        }
        return columns;
    }
}
