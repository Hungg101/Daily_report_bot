package com.example.dailyreportbot.repository;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:migration-governance-context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MigrationGovernanceTest {

    private static final List<MigrationBaseline> RELEASED_H2_BASELINE = List.of(
            new MigrationBaseline("1", "create users and daily reports", "SQL", 897298815),
            new MigrationBaseline("2", "add identity governance audit", "SQL", -2100961526),
            new MigrationBaseline("3", "add user optimistic version", "SQL", -987473603),
            new MigrationBaseline("4", "enforce identity admin audit immutability", "SQL", -259567325),
            new MigrationBaseline("5", "enforce database semantic constraints", "SQL", -2132455600),
            new MigrationBaseline("6", "add reminder tracking", "SQL", 2088595892),
            new MigrationBaseline("7", "use phone as user primary key", "SQL", -23760462),
            new MigrationBaseline("8", "add daily report collaborators", "SQL", 654288847),
            new MigrationBaseline("9", "add user roles and report organization", "SQL", -1286340367),
            new MigrationBaseline("10", "extend management audit actions", "SQL", 1857886104),
            new MigrationBaseline("11", "add daily report performer", "SQL", 1823685171)
    );

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void shouldUseTheFlywaySupportedH2TestVersion() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom)
                .contains("<h2.version>2.2.220</h2.version>")
                .contains("<artifactId>h2</artifactId>")
                .contains("<scope>test</scope>");
    }

    @Test
    void shouldKeepTheReleasedH2MigrationMetadataAndChecksums() {
        Flyway flyway = flywayFor(newDataSource("released-baseline"));

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
        assertThat(releasedMigrations(flyway).subList(0, RELEASED_H2_BASELINE.size()))
                .containsExactlyElementsOf(RELEASED_H2_BASELINE);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(flyway.info().current().getDescription())
                .isEqualTo("add daily report performer");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(entityManagerFactory.isOpen()).isTrue();
    }

    @Test
    void shouldRestoreV1ThroughV4TestBackupAndForwardRecoverV5ThroughV11(@TempDir Path temporaryDirectory) {
        DataSource sourceDataSource = newDataSource("recovery-source");
        Flyway sourceFlyway = flywayFor(sourceDataSource, "4");
        assertThat(sourceFlyway.migrate().migrationsExecuted).isEqualTo(4);

        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDataSource);
        long userId = insertUser(sourceJdbc, 99001L);
        insertReport(sourceJdbc, userId);
        insertAudit(sourceJdbc, userId);
        int sourceUserCount = count(sourceJdbc, "users");
        int sourceReportCount = count(sourceJdbc, "daily_reports");
        int sourceAuditCount = count(sourceJdbc, "identity_admin_audit_events");

        Path backup = temporaryDirectory.resolve("released-v4-backup.sql");
        sourceJdbc.execute("SCRIPT TO '" + h2Literal(backup) + "'");

        DataSource restoredDataSource = newDataSource("recovery-target");
        JdbcTemplate restoredJdbc = new JdbcTemplate(restoredDataSource);
        restoredJdbc.execute("RUNSCRIPT FROM '" + h2Literal(backup) + "'");

        Flyway restoredBeforeForward = flywayFor(restoredDataSource);
        assertThat(releasedMigrations(restoredBeforeForward)).containsExactlyElementsOf(RELEASED_H2_BASELINE.subList(0, 4));
        assertThat(count(restoredJdbc, "users")).isEqualTo(sourceUserCount);
        assertThat(count(restoredJdbc, "daily_reports")).isEqualTo(sourceReportCount);
        assertThat(count(restoredJdbc, "identity_admin_audit_events")).isEqualTo(sourceAuditCount);
        assertThat(restoredJdbc.queryForObject(
                "SELECT user_id FROM daily_reports",
                Long.class
        )).isEqualTo(userId);
        assertThat(restoredJdbc.queryForObject(
                "SELECT report_date FROM daily_reports",
                java.sql.Date.class
        ).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 14));

        assertThat(restoredBeforeForward.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(releasedMigrations(restoredBeforeForward).subList(0, RELEASED_H2_BASELINE.size()))
                .containsExactlyElementsOf(RELEASED_H2_BASELINE);
        assertThat(restoredBeforeForward.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(restoredBeforeForward.info().current().getDescription())
                .isEqualTo("add daily report performer");
        assertThat(restoredBeforeForward.validateWithResult().validationSuccessful).isTrue();
        assertThat(count(restoredJdbc, "users")).isEqualTo(sourceUserCount);
        assertThat(count(restoredJdbc, "daily_reports")).isEqualTo(sourceReportCount);
        assertThat(count(restoredJdbc, "identity_admin_audit_events")).isEqualTo(sourceAuditCount);
        assertThat(restoredJdbc.queryForObject(
                "SELECT user_id FROM daily_reports",
                Long.class
        )).isEqualTo(userId);
        assertThat(restoredJdbc.queryForObject(
                "SELECT report_date FROM daily_reports",
                java.sql.Date.class
        ).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 14));
        assertThat(restoredJdbc.queryForObject(
                "SELECT admin FROM users WHERE id = ?",
                Boolean.class,
                userId
        )).isFalse();
        assertThat(restoredJdbc.queryForObject(
                "SELECT manager FROM users WHERE id = ?",
                Boolean.class,
                userId
        )).isFalse();
        assertThat(restoredJdbc.queryForObject(
                "SELECT department FROM daily_reports",
                String.class
        )).isNull();
        assertThat(restoredJdbc.queryForObject(
                "SELECT unit FROM daily_reports",
                String.class
        )).isNull();
        assertThat(restoredJdbc.queryForObject(
                "SELECT performer FROM daily_reports",
                String.class
        )).isNull();
        validateHibernateMappingsAgainst(restoredDataSource);
    }

    private Flyway flywayFor(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .load();
    }

    private Flyway flywayFor(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/h2-migration")
                .target(target)
                .load();
    }

    private DataSource newDataSource(String purpose) {
        String database = purpose + "-" + UUID.randomUUID().toString().replace("-", "");
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + database + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
    }

    private List<MigrationBaseline> releasedMigrations(Flyway flyway) {
        return List.of(flyway.info().applied()).stream()
                .filter(info -> info.getVersion() != null)
                .map(info -> new MigrationBaseline(
                        info.getVersion().getVersion(),
                        info.getDescription(),
                        info.getType().name(),
                        info.getChecksum()
                ))
                .toList();
    }

    private long insertUser(JdbcTemplate jdbc, long telegramUserId) {
        jdbc.update(
                """
                INSERT INTO users (telegram_user_id, phone_number, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                """,
                telegramUserId,
                "+84" + telegramUserId,
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 14, 8, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 14, 8, 0))
        );
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE telegram_user_id = ?",
                Long.class,
                telegramUserId
        );
    }

    private void insertReport(JdbcTemplate jdbc, long userId) {
        jdbc.update(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, DATE '2026-07-14', 'recovery rehearsal report', ?)
                """,
                userId,
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 14, 9, 0))
        );
    }

    private void insertAudit(JdbcTemplate jdbc, long userId) {
        jdbc.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', 'recovery-gate', ?, 'generated test fixture', '{}', '{}', ?)
                """,
                userId,
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 14, 9, 1))
        );
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String h2Literal(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "/").replace("'", "''");
    }

    private void validateHibernateMappingsAgainst(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean recoveredMappings = new LocalContainerEntityManagerFactoryBean();
        recoveredMappings.setDataSource(dataSource);
        recoveredMappings.setPackagesToScan("com.example.dailyreportbot.entity");
        recoveredMappings.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        recoveredMappings.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));

        try {
            recoveredMappings.afterPropertiesSet();
            assertThat(recoveredMappings.getNativeEntityManagerFactory()).isNotNull();
        } finally {
            recoveredMappings.destroy();
        }
    }

    private record MigrationBaseline(String version, String description, String type, Integer checksum) {
    }
}
