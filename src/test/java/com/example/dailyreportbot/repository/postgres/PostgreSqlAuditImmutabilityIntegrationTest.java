package com.example.dailyreportbot.repository.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlAuditImmutabilityIntegrationTest extends PostgreSqlIntegrationTest {

    private static final AtomicLong TELEGRAM_ID = new AtomicLong(80_000);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allowsValidDirectAuditInsertAndHistoryReadWithStableNewestFirstOrdering() {
        long userId = insertUser();
        LocalDateTime timestamp = LocalDateTime.of(2026, 7, 13, 9, 0);
        long firstId = insertAudit(userId, "first-actor", "first reason", timestamp);
        long secondId = insertAudit(userId, "second-actor", "second reason", timestamp);

        List<Long> history = jdbc.queryForList(
                """
                SELECT id
                FROM identity_admin_audit_events
                WHERE target_user_id = ?
                ORDER BY created_at DESC, id DESC
                """,
                Long.class,
                userId
        );

        assertThat(history).containsExactly(secondId, firstId);
    }

    @Test
    void rejectsDirectAuditUpdateAndPreservesStoredEvent() {
        long userId = insertUser();
        long eventId = insertAudit(userId, "trusted-actor", "original reason", LocalDateTime.now());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE identity_admin_audit_events SET actor = 'rewritten' WHERE id = ?",
                eventId
        ))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("identity_admin_audit_events is append-only");
        assertThat(jdbc.queryForObject(
                "SELECT actor FROM identity_admin_audit_events WHERE id = ?",
                String.class,
                eventId
        )).isEqualTo("trusted-actor");
    }

    @Test
    void rejectsDirectAuditDeleteAndPreservesStoredEvent() {
        long userId = insertUser();
        long eventId = insertAudit(userId, "trusted-actor", "delete reason", LocalDateTime.now());

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM identity_admin_audit_events WHERE id = ?",
                eventId
        ))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("identity_admin_audit_events is append-only");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_admin_audit_events WHERE id = ?",
                Integer.class,
                eventId
        )).isEqualTo(1);
    }

    @Test
    void rejectsDirectAuditInsertWithBlankActorOrReason() {
        long userId = insertUser();

        assertThatThrownBy(() -> insertAudit(userId, "   ", "valid reason", LocalDateTime.now()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertAudit(userId, "valid actor", "   ", LocalDateTime.now()))
                .isInstanceOf(DataAccessException.class);
    }

    private long insertUser() {
        long telegramUserId = TELEGRAM_ID.incrementAndGet();
        return jdbc.queryForObject(
                """
                INSERT INTO users (telegram_user_id, phone_number, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                telegramUserId,
                "+84" + telegramUserId
        );
    }

    private long insertAudit(Long userId, String actor, String reason, LocalDateTime createdAt) {
        return jdbc.queryForObject(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, reason, before_state, after_state, created_at
                ) VALUES ('CREATE', ?, ?, ?, '{}', '{}', ?)
                RETURNING id
                """,
                Long.class,
                actor,
                userId,
                reason,
                Timestamp.valueOf(createdAt)
        );
    }
}
