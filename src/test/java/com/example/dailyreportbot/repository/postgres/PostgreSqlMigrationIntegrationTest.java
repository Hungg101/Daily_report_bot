package com.example.dailyreportbot.repository.postgres;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlMigrationIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void migratesFreshDatabaseThroughV11WithValidChecksumsAndNoReapplication() {
        List<MigrationInfo> applied = List.of(flyway.info().applied());

        assertThat(applied)
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(applied).allSatisfy(info -> {
            assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS);
            assertThat(info.getChecksum()).isNotNull();
        });
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
                Integer.class
        )).isEqualTo(11);
    }

    @Test
    void validatesHibernateMappingsAndRequiredColumns() {
        Set<String> entities = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(type -> type.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entities).contains(
                "User",
                "DailyReport",
                "IdentityAuditEvent",
                "ReminderOccurrence",
                "ReminderDeliveryAttempt"
        );
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'version'
                """
        )).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'collaborators'
                """
        )).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 500)
                .containsEntry("is_nullable", "YES");
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'performer'
                """
        )).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 255)
                .containsEntry("is_nullable", "YES");
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'admin'
                """
        )).containsEntry("data_type", "boolean")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "false");
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'manager'
                """
        )).containsEntry("data_type", "boolean")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "false");
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'department'
                """
        )).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 255)
                .containsEntry("is_nullable", "YES")
                .containsEntry("column_default", null);
        assertThat(jdbc.queryForMap(
                """
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'daily_reports'
                  AND column_name = 'unit'
                """
        )).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 255)
                .containsEntry("is_nullable", "YES")
                .containsEntry("column_default", null);
    }

    @Test
    void exposesNamedConstraintsAndIndexes() {
        assertThat(constraintNames("users")).contains(
                "pk_users",
                "uk_users_telegram_user_id",
                "uk_users_internal_id",
                "uk_users_employee_code",
                "ck_users_phone_number_not_blank",
                "ck_users_status",
                "ck_users_employee_code_normalized",
                "ck_users_employee_code_not_blank"
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
        assertThat(constraintNames("reminder_occurrences")).contains(
                "pk_reminder_occurrences",
                "fk_reminder_occurrences_user",
                "uk_reminder_occurrences_user_business_date",
                "ck_reminder_occurrences_state",
                "ck_reminder_occurrences_terminal_reason"
        );
        assertThat(constraintNames("reminder_delivery_attempts")).contains(
                "pk_reminder_delivery_attempts",
                "fk_reminder_delivery_attempts_occurrence",
                "uk_reminder_delivery_attempts_occurrence_number",
                "ck_reminder_delivery_attempts_number",
                "ck_reminder_delivery_attempts_state",
                "ck_reminder_delivery_attempts_failure_category"
        );
        assertThat(indexDefinition("idx_daily_reports_user_date_created"))
                .contains("(user_id, report_date, created_at DESC)");
        assertThat(indexDefinition("idx_identity_admin_audit_target_created"))
                .contains("(target_user_id, created_at DESC)");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_trigger trigger
                JOIN pg_class relation ON relation.oid = trigger.tgrelid
                JOIN pg_namespace schema ON schema.oid = relation.relnamespace
                WHERE trigger.tgname = 'trg_identity_admin_audit_events_immutable'
                  AND schema.nspname = 'public'
                  AND NOT trigger.tgisinternal
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void upgradesEligibleV6DatabaseThroughV11WithoutChangingHistoricalData() {
        String schema = "semantic_v6_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource upgradeDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        Flyway.configure()
                .dataSource(upgradeDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql-migration")
                .target("6")
                .load()
                .migrate();
        JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
        long userId = upgradeJdbc.queryForObject(
                """
                INSERT INTO %s.users (
                    telegram_user_id, phone_number, department_name, unit_name,
                    status, created_at, updated_at
                ) VALUES (
                    88001, '+8488001', 'Engineering', 'Platform',
                    'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """.formatted(schema),
                Long.class
        );
        long reportId = upgradeJdbc.queryForObject(
                """
                INSERT INTO %s.daily_reports (user_id, report_date, content, created_at)
                VALUES (?, DATE '2026-07-08', 'upgrade ownership evidence', CURRENT_TIMESTAMP)
                RETURNING id
                """.formatted(schema),
                Long.class,
                userId
        );
        Flyway upgraded = Flyway.configure()
                .dataSource(upgradeDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/postgresql-migration")
                .load();

        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(List.of(upgraded.info().applied()))
                .filteredOn(info -> info.getVersion() != null)
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(upgradeJdbc.queryForObject(
                "SELECT user_id FROM %s.daily_reports WHERE id = ?".formatted(schema),
                Long.class,
                reportId
        )).isEqualTo(userId);
        assertThat(upgradeJdbc.queryForObject(
                "SELECT collaborators FROM %s.daily_reports WHERE id = ?".formatted(schema),
                String.class,
                reportId
        )).isNull();
        assertThat(upgradeJdbc.queryForObject(
                "SELECT department FROM %s.daily_reports WHERE id = ?".formatted(schema),
                String.class,
                reportId
        )).isNull();
        assertThat(upgradeJdbc.queryForObject(
                "SELECT unit FROM %s.daily_reports WHERE id = ?".formatted(schema),
                String.class,
                reportId
        )).isNull();
        assertThat(upgradeJdbc.queryForObject(
                "SELECT performer FROM %s.daily_reports WHERE id = ?".formatted(schema),
                String.class,
                reportId
        )).isNull();
        assertThat(upgradeJdbc.queryForMap(
                """
                SELECT department_name, unit_name, admin, manager
                FROM %s.users
                WHERE id = ?
                """.formatted(schema),
                userId
        )).containsEntry("department_name", "Engineering")
                .containsEntry("unit_name", "Platform")
                .containsEntry("admin", false)
                .containsEntry("manager", false);
        assertThat(upgradeJdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE constraint_schema = ? AND table_name = 'users' AND constraint_name = 'pk_users'
                ORDER BY ordinal_position
                """,
                String.class,
                schema
        )).containsExactly("phone_number");
    }

    @Test
    void enforcesRequiredUniqueIdentifiers() {
        long firstId = insertUser(91001L, "EMP-91", "+8491001");
        insertUser(91002L, null, "+8491002");
        insertUser(91003L, null, "+8491003");

        assertThat(firstId).isPositive();
        assertThatThrownBy(() -> insertUser(91001L, "EMP-92", "+8491012"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(91004L, "EMP-91", "+8491004"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(91005L, "EMP-95", "+8491001"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(91006L, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(91007L, null, " \t"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesReportOwnershipAndAllowsMultipleReportsPerOwnerAndDate() {
        long userId = insertUser(92001L, null, "+8492001");

        assertThatThrownBy(() -> insertReport(999999L, "orphan"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertReport(userId, "first");
        insertReport(userId, "second");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_reports WHERE user_id = ? AND report_date = DATE '2026-07-08'",
                Integer.class,
                userId
        )).isEqualTo(2);
    }

    @Test
    void enforcesNonWhitespaceReportContentEmployeeCodesAndAuditContext() {
        long userId = insertUser(93001L, null, "+8493001");

        assertThatThrownBy(() -> insertReport(userId, ""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReport(userId, " \t\r\n "))
                .isInstanceOf(DataIntegrityViolationException.class);

        long reportId = insertReport(userId, " valid report ");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE daily_reports SET content = ? WHERE id = ?",
                "\n",
                reportId
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertUser(93002L, "", "+8493002"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertUser(93003L, "\t", "+8493003"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET employee_code = ? WHERE id = ?",
                " \r\n ",
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);

        long validEmployeeUserId = insertUser(93004L, "EMP-VALID", "+8493004");
        assertThat(validEmployeeUserId).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT employee_code FROM users WHERE id = ?",
                String.class,
                validEmployeeUserId
        )).isEqualTo("EMP-VALID");

        assertThatThrownBy(() -> insertAudit(userId, "\t", "valid reason"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAudit(userId, "valid actor", "\n"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> constraintNames(String tableName) {
        return jdbc.queryForList(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = ?
                """,
                String.class,
                tableName
        );
    }

    private String indexDefinition(String indexName) {
        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class,
                indexName
        );
    }

    private long insertUser(Long telegramUserId, String employeeCode, String phoneNumber) {
        return jdbc.queryForObject(
                """
                INSERT INTO users (
                    telegram_user_id, employee_code, phone_number, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                telegramUserId,
                employeeCode,
                phoneNumber
        );
    }

    private long insertReport(long userId, String content) {
        return jdbc.queryForObject(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, DATE '2026-07-08', ?, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                userId,
                content
        );
    }

    private void insertAudit(long userId, String actor, String reason) {
        jdbc.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', ?, ?, ?, '{}', '{}', CURRENT_TIMESTAMP)
                """,
                actor,
                userId,
                reason
        );
    }
}
