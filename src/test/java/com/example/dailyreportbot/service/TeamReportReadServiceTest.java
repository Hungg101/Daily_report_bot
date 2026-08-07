package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.dailyreportbot.service.TeamReportTestFixtures.report;
import static com.example.dailyreportbot.service.TeamReportTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamReportReadServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private DailyReportRepository reportRepository;

    private TeamReportReadService service;
    private final LocalDate date = LocalDate.of(2026, 7, 3);

    @BeforeEach
    void setUp() {
        service = new TeamReportReadService(userRepository, reportRepository, new TeamReportPolicy());
    }

    @Test
    void shouldReadIndividualStatusWithNewestFirstEvidenceAndEarliestClassification() {
        User user = user(7L, "  Nguyen An  ", "Engineering", "Platform", UserStatus.ACTIVE);
        user.setEmployeeCode("EMP007");
        user.setFirstName("An");
        DailyReport late = report(12L, user, date, date.atTime(18, 0));
        DailyReport onTime = report(11L, user, date, date.atTime(16, 59, 59));
        DailyReport future = report(13L, user, date, date.atTime(19, 0));
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(reportRepository.findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(7L, date))
                .thenReturn(List.of(future, late, onTime));

        TeamMemberReportStatus result = service.readUserStatus(
                7L,
                date,
                Instant.parse("2026-07-03T11:00:00Z"),
                "Asia/Ho_Chi_Minh"
        );

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.displayName()).isEqualTo("Nguyen An");
        assertThat(result.status()).isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
        assertThat(result.firstSubmissionAt()).isEqualTo(date.atTime(16, 59, 59));
        assertThat(result.reports()).extracting(TeamReportEvidence::reportId).containsExactly(12L, 11L);
        verify(userRepository, never()).save(any(User.class));
        verify(reportRepository, never()).save(any(DailyReport.class));
    }

    @Test
    void shouldRejectMalformedTimezoneBeforeReadingData() {
        assertThatThrownBy(() -> service.readUserStatus(
                7L,
                date,
                Instant.parse("2026-07-03T10:00:00Z"),
                "Not/A_Zone"
        )).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(userRepository, reportRepository);
    }

    @Test
    void shouldRejectEvaluationBeforeBusinessDate() {
        assertThatThrownBy(() -> service.readUserStatus(
                7L,
                date,
                Instant.parse("2026-07-02T10:00:00Z"),
                "Asia/Ho_Chi_Minh"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before business date");

        verifyNoInteractions(userRepository, reportRepository);
    }

    @Test
    void shouldUseExplicitTimezoneInsteadOfServerDefaultForGraceBoundary() {
        User user = user(7L, "Timezone", "Engineering", null, UserStatus.ACTIVE);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(reportRepository.findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(7L, date))
                .thenReturn(List.of());
        Instant evaluatedAt = Instant.parse("2026-07-03T10:30:01Z");

        TeamMemberReportStatus asia = service.readUserStatus(7L, date, evaluatedAt, "Asia/Ho_Chi_Minh");
        TeamMemberReportStatus utc = service.readUserStatus(7L, date, evaluatedAt, "UTC");

        assertThat(asia.status()).isEqualTo(TeamReportStatus.ABSENT);
        assertThat(utc.status()).isEqualTo(TeamReportStatus.DUE);
    }

    @Test
    void shouldRejectMissingUserDeterministically() {
        when(userRepository.findByInternalId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readUserStatus(
                99L,
                date,
                Instant.parse("2026-07-03T10:00:00Z"),
                "Asia/Ho_Chi_Minh"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void shouldBuildReconciledTeamSummaryWithCurrentRosterAndDeterministicOrder() {
        TeamScope scope = new TeamScope("  Engineering  ", "  Platform  ");
        User onTime = user(2L, "Alpha", "Engineering", "Platform", UserStatus.ACTIVE);
        User absent = user(1L, " alpha ", "Engineering", "Platform", UserStatus.ACTIVE);
        User inactive = user(3L, "Zebra", "Engineering", "Platform", UserStatus.INACTIVE);
        DailyReport lateReport = report(22L, onTime, date, date.atTime(18, 0));
        DailyReport onTimeReport = report(21L, onTime, date, date.atTime(17, 0));
        when(userRepository.findCurrentTeamMembers("Engineering", "Platform"))
                .thenReturn(List.of(inactive, onTime, absent));
        when(reportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
                List.of(3L, 2L, 1L),
                date
        )).thenReturn(List.of(lateReport, onTimeReport));

        TeamReportSummary summary = service.readTeamSummary(
                scope,
                date,
                Instant.parse("2026-07-03T11:00:00Z"),
                "Asia/Ho_Chi_Minh"
        );

        assertThat(summary.totalEmployees()).isEqualTo(3);
        assertThat(summary.members()).extracting(TeamMemberReportStatus::userId)
                .containsExactly(1L, 2L, 3L);
        assertThat(summary.count(TeamReportStatus.SUBMITTED_ON_TIME)).isEqualTo(1);
        assertThat(summary.count(TeamReportStatus.ABSENT)).isEqualTo(1);
        assertThat(summary.count(TeamReportStatus.EXCLUDED)).isEqualTo(1);
        assertThat(summary.count(TeamReportStatus.DUE)).isZero();
        assertThat(summary.counts().values()).contains(1L, 0L);
        assertThat(summary.members().get(1).reports()).extracting(TeamReportEvidence::reportId)
                .containsExactly(22L, 21L);
    }

    @Test
    void shouldReturnEmptySuccessfulSummaryWithoutLoadingReports() {
        TeamScope scope = new TeamScope("Missing", null);
        when(userRepository.findCurrentTeamMembers("Missing", null)).thenReturn(List.of());

        TeamReportSummary summary = service.readTeamSummary(
                scope,
                date,
                Instant.parse("2026-07-03T11:00:00Z"),
                "Asia/Ho_Chi_Minh"
        );

        assertThat(summary.totalEmployees()).isZero();
        assertThat(summary.members()).isEmpty();
        assertThat(summary.counts()).containsOnlyKeys(TeamReportStatus.values());
        assertThat(summary.counts().values()).containsOnly(0L);
        verify(reportRepository, never()).findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(any(), any());
    }

    @Test
    void shouldReadOrganizationSummaryAcrossDepartments() {
        User engineering = user(10L, "Engineering user", "Engineering", "Platform", UserStatus.ACTIVE);
        User finance = user(11L, "Finance user", "Finance", "Accounting", UserStatus.ACTIVE);
        when(userRepository.findAll()).thenReturn(List.of(engineering, finance));
        when(reportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(List.of(10L, 11L), date))
                .thenReturn(List.of());

        TeamReportSummary summary = service.readOrganizationSummary(
                date,
                Instant.parse("2026-07-03T11:00:00Z"),
                "Asia/Ho_Chi_Minh"
        );

        assertThat(summary.scope().departmentName()).isEqualTo("Toàn hệ thống");
        assertThat(summary.totalEmployees()).isEqualTo(2);
        assertThat(summary.members()).extracting(TeamMemberReportStatus::departmentName)
                .containsExactly("Engineering", "Finance");
    }

    @Test
    void shouldReturnEquivalentImmutableIndividualAndTeamResults() {
        User user = user(7L, "Parity", "Engineering", null, UserStatus.ACTIVE);
        DailyReport report = report(31L, user, date, date.atTime(16, 0));
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(userRepository.findCurrentTeamMembers("Engineering", null)).thenReturn(List.of(user));
        when(reportRepository.findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(7L, date))
                .thenReturn(List.of(report));
        when(reportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(List.of(7L), date))
                .thenReturn(List.of(report));
        Instant evaluatedAt = Instant.parse("2026-07-03T11:00:00Z");

        TeamMemberReportStatus individual = service.readUserStatus(7L, date, evaluatedAt, "Asia/Ho_Chi_Minh");
        TeamReportSummary summary = service.readTeamSummary(
                new TeamScope("Engineering", null),
                date,
                evaluatedAt,
                "Asia/Ho_Chi_Minh"
        );
        TeamMemberReportStatus teamMember = summary.members().get(0);

        assertThat(teamMember.status()).isEqualTo(individual.status());
        assertThat(teamMember.reports()).isEqualTo(individual.reports());
        assertThatThrownBy(() -> summary.members().add(individual))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> summary.counts().put(TeamReportStatus.DUE, 99L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> individual.reports().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectInvalidInputsAsIllegalArgumentsBeforeReadingData() {
        Instant evaluatedAt = Instant.parse("2026-07-03T11:00:00Z");

        assertThatThrownBy(() -> new TeamScope("  ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readTeamSummary(null, date, evaluatedAt, "Asia/Ho_Chi_Minh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readUserStatus(7L, null, evaluatedAt, "Asia/Ho_Chi_Minh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readUserStatus(7L, date, null, "Asia/Ho_Chi_Minh"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readUserStatus(0L, date, evaluatedAt, "Asia/Ho_Chi_Minh"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(userRepository, reportRepository);
    }

    @Test
    void shouldDeclareTheWholeReadModelTransactionallyReadOnly() {
        Transactional transactional = TeamReportReadService.class.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
