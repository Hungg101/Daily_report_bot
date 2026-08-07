package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamReportPolicyTest {

    private final TeamReportPolicy policy = new TeamReportPolicy();
    private final LocalDate workday = LocalDate.of(2026, 7, 3);

    @Test
    void shouldClassifySubmissionAroundDeadlineUsingEarliestQualifyingReport() {
        assertThat(evaluateAt(List.of(at(16, 59, 59)), at(17, 0, 0)))
                .isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
        assertThat(evaluateAt(List.of(at(17, 0, 0)), at(17, 0, 0)))
                .isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
        assertThat(evaluateAt(List.of(at(17, 0, 1)), at(17, 0, 1)))
                .isEqualTo(TeamReportStatus.SUBMITTED_LATE);
        assertThat(evaluateAt(List.of(at(18, 0, 0), at(16, 0, 0)), at(18, 0, 0)))
                .isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
    }

    @Test
    void shouldKeepMissingReportDueThroughGraceEndpointThenMarkAbsent() {
        assertThat(evaluateAt(List.of(), at(17, 29, 59))).isEqualTo(TeamReportStatus.DUE);
        assertThat(evaluateAt(List.of(), at(17, 30, 0))).isEqualTo(TeamReportStatus.DUE);
        assertThat(evaluateAt(List.of(), at(17, 30, 1))).isEqualTo(TeamReportStatus.ABSENT);
    }

    @Test
    void shouldTreatMondayThroughFridayAsWorkdaysAndWeekendAsExcluded() {
        assertThat(evaluateAt(List.of(), at(12, 0, 0))).isEqualTo(TeamReportStatus.DUE);

        LocalDate saturday = workday.plusDays(1);
        LocalDate sunday = workday.plusDays(2);
        LocalDate monday = workday.plusDays(3);
        assertThat(policy.evaluate(UserStatus.ACTIVE, saturday, saturday.atTime(12, 0), List.of()))
                .isEqualTo(TeamReportStatus.EXCLUDED);
        assertThat(policy.evaluate(UserStatus.ACTIVE, sunday, sunday.atTime(18, 0), List.of()))
                .isEqualTo(TeamReportStatus.EXCLUDED);
        assertThat(policy.evaluate(UserStatus.ACTIVE, monday, monday.atTime(12, 0), List.of()))
                .isEqualTo(TeamReportStatus.DUE);
    }

    @Test
    void shouldRemainDueBeforeEightAndAcceptEarlySubmissionAsOnTime() {
        assertThat(evaluateAt(List.of(), at(7, 59, 59))).isEqualTo(TeamReportStatus.DUE);
        assertThat(evaluateAt(List.of(), at(8, 0, 0))).isEqualTo(TeamReportStatus.DUE);
        assertThat(evaluateAt(List.of(), at(8, 0, 1))).isEqualTo(TeamReportStatus.DUE);
        assertThat(evaluateAt(List.of(at(7, 59, 59)), at(8, 0, 0)))
                .isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
    }

    @Test
    void shouldExcludeCurrentlyInactiveUserEvenWithReport() {
        assertThat(policy.evaluate(UserStatus.INACTIVE, workday, at(18, 0, 0), List.of(at(9, 0, 0))))
                .isEqualTo(TeamReportStatus.EXCLUDED);
    }

    @Test
    void shouldIgnoreSubmissionsAfterEvaluationInstant() {
        assertThat(policy.evaluate(UserStatus.ACTIVE, workday, at(16, 0, 0), List.of(at(16, 0, 1))))
                .isEqualTo(TeamReportStatus.DUE);
    }

    @Test
    void shouldMarkLateSubmissionAfterGraceAsSubmittedLate() {
        assertThat(evaluateAt(List.of(at(18, 0, 0)), at(18, 0, 0)))
                .isEqualTo(TeamReportStatus.SUBMITTED_LATE);
    }

    @Test
    void shouldTreatNextDayMorningSubmissionAsLateForStoredBusinessDate() {
        LocalDateTime nextMorning = workday.plusDays(1).atTime(8, 0);

        assertThat(policy.evaluate(UserStatus.ACTIVE, workday, nextMorning, List.of(nextMorning)))
                .isEqualTo(TeamReportStatus.SUBMITTED_LATE);
    }

    private TeamReportStatus evaluateAt(List<LocalDateTime> submissions, LocalDateTime evaluatedAt) {
        return policy.evaluate(UserStatus.ACTIVE, workday, evaluatedAt, submissions);
    }

    private LocalDateTime at(int hour, int minute, int second) {
        return workday.atTime(hour, minute, second);
    }
}
