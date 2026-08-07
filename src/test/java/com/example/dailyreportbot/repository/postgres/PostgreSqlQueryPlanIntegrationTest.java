package com.example.dailyreportbot.repository.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlQueryPlanIntegrationTest extends PostgreSqlIntegrationTest {

    private static final AtomicLong TELEGRAM_ID = new AtomicLong(120_000);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 13);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void capturesCompletePlansForCurrentReportRosterAndAuditReadWorkloads() {
        QueryPlanFixture fixture = seedFixture();
        jdbc.execute("ANALYZE users");
        jdbc.execute("ANALYZE daily_reports");
        jdbc.execute("ANALYZE identity_admin_audit_events");

        List<PlanEvidence> plans = List.of(
                explain(
                        "personal_cross_date_history",
                        """
                        SELECT report.id
                        FROM daily_reports report
                        JOIN users owner ON owner.id = report.user_id
                        WHERE owner.telegram_user_id = ?
                        ORDER BY report.created_at DESC
                        LIMIT 5
                        """,
                        fixture.focusTelegramUserId()
                ),
                explain(
                        "user_date_reports",
                        """
                        SELECT report.id
                        FROM daily_reports report
                        WHERE report.user_id = ?
                          AND report.report_date = ?
                        ORDER BY report.created_at DESC, report.id DESC
                        """,
                        fixture.focusUserId(),
                        BUSINESS_DATE
                ),
                explain(
                        "team_date_batch_reports",
                        teamDateBatchSql(fixture.teamUserIds().size()),
                        teamDateBatchArguments(fixture)
                ),
                explain(
                        "normalized_team_roster",
                        """
                        SELECT user_account.id
                        FROM users user_account
                        WHERE lower(trim(user_account.department_name)) = lower(?)
                          AND (? IS NULL OR lower(trim(user_account.unit_name)) = lower(?))
                        """,
                        "Engineering",
                        "Platform",
                        "Platform"
                ),
                explain(
                        "target_or_related_audit_history",
                        """
                        SELECT event.id
                        FROM identity_admin_audit_events event
                        WHERE event.target_user_id = ?
                           OR event.related_user_id = ?
                        ORDER BY event.created_at DESC, event.id DESC
                        LIMIT 100
                        """,
                        fixture.focusUserId(),
                        fixture.focusUserId()
                )
        );

        assertThat(plans).extracting(PlanEvidence::workload).containsExactly(
                "personal_cross_date_history",
                "user_date_reports",
                "team_date_batch_reports",
                "normalized_team_roster",
                "target_or_related_audit_history"
        );
        plans.forEach(this::assertCompletePlan);

        List<Long> expectedFocusReportOrder = new ArrayList<>(fixture.focusBusinessDateReportIds());
        java.util.Collections.reverse(expectedFocusReportOrder);
        List<Long> focusBusinessDateReports = jdbc.queryForList(
                """
                SELECT id
                FROM daily_reports
                WHERE user_id = ? AND report_date = ?
                ORDER BY created_at DESC, id DESC
                """,
                Long.class,
                fixture.focusUserId(),
                BUSINESS_DATE
        );
        assertThat(focusBusinessDateReports.subList(0, expectedFocusReportOrder.size()))
                .containsExactlyElementsOf(expectedFocusReportOrder);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM identity_admin_audit_events
                WHERE target_user_id = ? OR related_user_id = ?
                """,
                Integer.class,
                fixture.focusUserId(),
                fixture.focusUserId()
        )).isEqualTo(12);
    }

    private void assertCompletePlan(PlanEvidence evidence) {
        assertThat(evidence.plan())
                .as("plan for %s", evidence.workload())
                .contains("(actual time=", "rows=", "Buffers:", "Planning Time:", "Execution Time:");
    }

    private PlanEvidence explain(String workload, String query, Object... arguments) {
        List<String> lines = jdbc.query(
                "EXPLAIN (ANALYZE, BUFFERS) " + query,
                (resultSet, rowNumber) -> resultSet.getString(1),
                arguments
        );
        PlanEvidence evidence = new PlanEvidence(workload, String.join(System.lineSeparator(), lines));
        System.out.println("DBH-006 plan [" + workload + "]");
        System.out.println(evidence.plan());
        return evidence;
    }

    private String teamDateBatchSql(int userCount) {
        return """
                SELECT report.id
                FROM daily_reports report
                WHERE report.user_id IN (%s)
                  AND report.report_date = ?
                ORDER BY report.created_at DESC, report.id DESC
                """.formatted(String.join(", ", java.util.Collections.nCopies(userCount, "?")));
    }

    private Object[] teamDateBatchArguments(QueryPlanFixture fixture) {
        Object[] arguments = new Object[fixture.teamUserIds().size() + 1];
        for (int index = 0; index < fixture.teamUserIds().size(); index++) {
            arguments[index] = fixture.teamUserIds().get(index);
        }
        arguments[arguments.length - 1] = BUSINESS_DATE;
        return arguments;
    }

    private QueryPlanFixture seedFixture() {
        long focusTelegramUserId = TELEGRAM_ID.incrementAndGet();
        long focusUserId = insertUser(
                focusTelegramUserId,
                "Focus",
                "  Engineering ",
                " Platform "
        );
        long relatedUserId = insertUser(
                TELEGRAM_ID.incrementAndGet(),
                "Related",
                "engineering",
                "platform"
        );
        List<Long> teamUserIds = new ArrayList<>(List.of(focusUserId, relatedUserId));
        for (int index = 0; index < 22; index++) {
            teamUserIds.add(insertUser(
                    TELEGRAM_ID.incrementAndGet(),
                    "Platform " + index,
                    index % 2 == 0 ? "ENGINEERING" : "Engineering",
                    index % 2 == 0 ? "platform" : " PLATFORM "
            ));
        }
        for (int index = 0; index < 12; index++) {
            insertUser(TELEGRAM_ID.incrementAndGet(), "QA " + index, "Engineering", "QA");
            insertUser(TELEGRAM_ID.incrementAndGet(), "Operations " + index, "Operations", "Platform");
        }

        List<Long> focusBusinessDateReportIds = new ArrayList<>();
        LocalDateTime sameCreatedAt = BUSINESS_DATE.atTime(16, 45);
        for (int index = 0; index < 3; index++) {
            focusBusinessDateReportIds.add(insertReport(
                    focusUserId,
                    BUSINESS_DATE,
                    sameCreatedAt,
                    "focus business-date report " + index
            ));
        }
        for (int dayOffset = 1; dayOffset <= 3; dayOffset++) {
            for (int reportIndex = 0; reportIndex < 3; reportIndex++) {
                insertReport(
                        focusUserId,
                        BUSINESS_DATE.minusDays(dayOffset),
                        BUSINESS_DATE.minusDays(dayOffset).atTime(9, reportIndex),
                        "focus history report " + dayOffset + "-" + reportIndex
                );
            }
        }
        for (Long teamUserId : teamUserIds) {
            insertReport(teamUserId, BUSINESS_DATE, BUSINESS_DATE.atTime(9, 0), "team morning report");
            insertReport(teamUserId, BUSINESS_DATE, BUSINESS_DATE.atTime(16, 0), "team afternoon report");
        }

        for (int index = 0; index < 6; index++) {
            insertAudit(focusUserId, null, BUSINESS_DATE.atTime(10, 0));
            insertAudit(relatedUserId, focusUserId, BUSINESS_DATE.atTime(10, 0));
        }
        for (int index = 0; index < 12; index++) {
            insertAudit(teamUserIds.get((index % (teamUserIds.size() - 1)) + 1), null, BUSINESS_DATE.atTime(11, 0));
        }

        return new QueryPlanFixture(
                focusUserId,
                focusTelegramUserId,
                List.copyOf(teamUserIds),
                List.copyOf(focusBusinessDateReportIds)
        );
    }

    private long insertUser(long telegramUserId, String firstName, String departmentName, String unitName) {
        return jdbc.queryForObject(
                """
                INSERT INTO users (
                    telegram_user_id, phone_number, first_name, department_name, unit_name,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                telegramUserId,
                "+84" + telegramUserId,
                firstName,
                departmentName,
                unitName
        );
    }

    private long insertReport(long userId, LocalDate reportDate, LocalDateTime createdAt, String content) {
        return jdbc.queryForObject(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                reportDate,
                content,
                Timestamp.valueOf(createdAt)
        );
    }

    private void insertAudit(long targetUserId, Long relatedUserId, LocalDateTime createdAt) {
        jdbc.update(
                """
                INSERT INTO identity_admin_audit_events (
                    action, actor, target_user_id, related_user_id, reason, before_state, after_state, created_at
                ) VALUES ('UPDATE_PROFILE', 'query-plan-fixture', ?, ?, 'query plan fixture', '{}', '{}', ?)
                """,
                targetUserId,
                relatedUserId,
                Timestamp.valueOf(createdAt)
        );
    }

    private record QueryPlanFixture(
            long focusUserId,
            long focusTelegramUserId,
            List<Long> teamUserIds,
            List<Long> focusBusinessDateReportIds
    ) {
    }

    private record PlanEvidence(String workload, String plan) {
    }
}
