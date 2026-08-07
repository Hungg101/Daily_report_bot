package com.example.dailyreportbot.repository.postgres;

import com.example.dailyreportbot.service.IdentityAdminResult;
import com.example.dailyreportbot.service.IdentityAdminStatus;
import com.example.dailyreportbot.service.IdentityAdministrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlManagementMutationIntegrationTest extends PostgreSqlIntegrationTest {

    private static final AtomicLong TELEGRAM_ID = new AtomicLong(95_000);

    @Autowired
    private IdentityAdministrationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void acceptsV10ReportActionsAndKeepsTheirAuditRowsAppendOnly() {
        long actorTelegramId = TELEGRAM_ID.incrementAndGet();
        insertUser(actorTelegramId, true);
        long ownerId = insertUser(TELEGRAM_ID.incrementAndGet(), false);
        long editReportId = insertReport(ownerId, "postgres edit body");
        long deleteReportId = insertReport(ownerId, "postgres delete body");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success",
                Integer.class
        )).isEqualTo(1);

        IdentityAdminResult editResult = service.editReport(
                actorTelegramId,
                editReportId,
                "postgres edited body",
                "correct report"
        );
        IdentityAdminResult deleteResult = service.deleteReport(
                actorTelegramId,
                deleteReportId,
                "remove duplicate"
        );

        assertThat(editResult.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(deleteResult.status()).isEqualTo(IdentityAdminStatus.DELETED);
        List<Long> eventIds = jdbc.queryForList(
                "SELECT id FROM identity_admin_audit_events WHERE target_user_id = ? ORDER BY id",
                Long.class,
                ownerId
        );
        assertThat(eventIds).hasSize(2);
        assertThat(jdbc.queryForList(
                "SELECT action FROM identity_admin_audit_events WHERE target_user_id = ? ORDER BY id",
                String.class,
                ownerId
        )).containsExactly("UPDATE_REPORT", "DELETE_REPORT");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE identity_admin_audit_events SET actor = 'rewritten' WHERE id = ?",
                eventIds.get(0)
        ))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("identity_admin_audit_events is append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM identity_admin_audit_events WHERE id = ?",
                eventIds.get(1)
        ))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("identity_admin_audit_events is append-only");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_admin_audit_events WHERE target_user_id = ?",
                Integer.class,
                ownerId
        )).isEqualTo(2);
    }

    private long insertUser(long telegramUserId, boolean admin) {
        return jdbc.queryForObject(
                """
                INSERT INTO users (
                    telegram_user_id, phone_number, full_name, department_name, unit_name,
                    admin, manager, status, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'Engineering', 'Platform', ?, FALSE, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """,
                Long.class,
                telegramUserId,
                "+84" + telegramUserId,
                "User " + telegramUserId,
                admin
        );
    }

    private long insertReport(long ownerId, String content) {
        return jdbc.queryForObject(
                """
                INSERT INTO daily_reports (
                    user_id, report_date, content, department, unit, created_at
                ) VALUES (
                    ?, DATE '2026-07-02', ?, 'Submitted Engineering', 'Submitted Platform',
                    TIMESTAMP '2026-07-02 09:30:00'
                )
                RETURNING id
                """,
                Long.class,
                ownerId,
                content
        );
    }
}
