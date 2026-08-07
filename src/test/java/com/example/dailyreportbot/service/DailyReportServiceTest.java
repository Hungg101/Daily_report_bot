package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyReportServiceTest {

    private DailyReportRepository reportRepository;
    private UserRepository userRepository;
    private DailyReportService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(DailyReportRepository.class);
        userRepository = mock(UserRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-16T17:30:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new DailyReportService(reportRepository, userRepository, clock);
    }

    @Test
    void shouldSaveReportForUser() {
        User user = activeUser(7L, 12345L);
        stubCurrentUser(user);

        DailyReportSubmissionStatus status = service.submitToday(
                12345L,
                "  Hoàn thành API  ",
                "Trần Thị B",
                Instant.parse("2026-06-16T17:30:00Z")
        );

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(status).isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getReportDate()).isEqualTo(LocalDate.of(2026, 6, 17));
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 0, 30));
        assertThat(captor.getValue().getPerformer()).isEqualTo("Telegram user ID 12345");
        assertThat(captor.getValue().getContent()).isEqualTo("Hoàn thành API");
        assertThat(captor.getValue().getCollaborators()).isEqualTo("Trần Thị B");
    }

    @Test
    void shouldUseProvidedSubmissionInstantForPersistedDateAndTimestamp() {
        User user = activeUser(7L, 12345L);
        stubCurrentUser(user);
        Instant submissionInstant = Instant.parse("2026-06-16T16:59:59Z");

        DailyReportSubmissionStatus status = service.submitToday(
                12345L,
                "Boundary report",
                submissionInstant
        );

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(status).isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(captor.getValue().getReportDate()).isEqualTo(LocalDate.of(2026, 6, 16));
        assertThat(captor.getValue().getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 16, 23, 59, 59));
    }

    @Test
    void shouldRejectBlankContentWithoutLookingUpUser() {
        assertThat(service.submitToday(12345L, "  ")).isEqualTo(DailyReportSubmissionStatus.BLANK_CONTENT);

        verifyNoInteractions(userRepository, reportRepository);
    }

    @Test
    void shouldRejectSubmissionWhenUserIsMissing() {
        when(userRepository.findInternalIdByTelegramUserId(12345L)).thenReturn(Optional.empty());

        assertThat(service.submitToday(12345L, "Hoàn thành API"))
                .isEqualTo(DailyReportSubmissionStatus.TELEGRAM_USER_NOT_FOUND);

        verify(reportRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectNewSubmissionForInactiveUser() {
        User user = activeUser(7L, 12345L);
        user.setStatus(UserStatus.INACTIVE);
        stubCurrentUser(user);

        assertThat(service.submitToday(12345L, "Should not be stored"))
                .isEqualTo(DailyReportSubmissionStatus.USER_INACTIVE);

        verify(reportRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldAllowSubmissionAgainForReactivatedSameUser() {
        User user = activeUser(7L, 12345L);
        stubCurrentUser(user);

        assertThat(service.submitToday(12345L, "Back to work"))
                .isEqualTo(DailyReportSubmissionStatus.SAVED);

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getUser().getId()).isEqualTo(7L);
    }

    @Test
    void shouldAllowMultipleReportsForSameUserAndDate() {
        User user = activeUser(7L, 12345L);
        stubCurrentUser(user);

        assertThat(service.submitToday(12345L, "First structured report"))
                .isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(service.submitToday(12345L, "Second structured report"))
                .isEqualTo(DailyReportSubmissionStatus.SAVED);

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(reportRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DailyReport::getContent)
                .containsExactly("First structured report", "Second structured report");
        assertThat(captor.getAllValues())
                .extracting(DailyReport::getReportDate)
                .containsOnly(LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldCaptureOrganizationSeparatelyForEachSubmission() {
        User user = activeUser(7L, 12345L);
        user.setDepartmentName("Engineering");
        user.setUnitName("Platform");
        stubCurrentUser(user);

        assertThat(service.submitToday(12345L, "Before organization change"))
                .isEqualTo(DailyReportSubmissionStatus.SAVED);
        user.setDepartmentName(null);
        user.setUnitName(null);
        assertThat(service.submitToday(12345L, "After organization removal"))
                .isEqualTo(DailyReportSubmissionStatus.SAVED);

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(reportRepository, times(2)).save(captor.capture());
        DailyReport first = captor.getAllValues().get(0);
        DailyReport second = captor.getAllValues().get(1);
        assertThat(first.getDepartment()).isEqualTo("Engineering");
        assertThat(first.getUnit()).isEqualTo("Platform");
        assertThat(second.getDepartment()).isNull();
        assertThat(second.getUnit()).isNull();
    }

    @Test
    void shouldSaveWhenExpectedOwnerStillHasTelegramIdentity() {
        User user = activeUser(7L, 12345L);
        when(userRepository.findLockedById(7L)).thenReturn(Optional.of(user));

        assertThat(service.submitToday(
                12345L,
                7L,
                "Previewed report",
                "Không có",
                Instant.parse("2026-06-16T17:30:00Z")
        )).isEqualTo(DailyReportSubmissionStatus.SAVED);

        verify(reportRepository).save(org.mockito.ArgumentMatchers.any(DailyReport.class));
    }

    @Test
    void shouldRejectWhenExpectedOwnerWasRemapped() {
        User user = activeUser(7L, 99999L);
        when(userRepository.findLockedById(7L)).thenReturn(Optional.of(user));

        assertThat(service.submitToday(
                12345L,
                7L,
                "Stale preview",
                "Không có",
                Instant.parse("2026-06-16T17:30:00Z")
        )).isEqualTo(DailyReportSubmissionStatus.IDENTITY_CHANGED);

        verify(reportRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldSaveStructuredMiniAppDetailsThroughTheSameLockedBoundary() {
        User user = activeUser(7L, 12345L);
        user.setFullName("Nguyễn Văn A");
        stubCurrentUser(user);

        DailyReportSubmissionStatus status = service.submitToday(
                12345L,
                new ReportSubmissionDetails("Mini App", "Không có", "Hoàn thành"),
                Instant.parse("2026-06-16T17:30:00Z")
        );

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(reportRepository).save(captor.capture());
        assertThat(status).isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(captor.getValue().getContent()).isEqualTo("""
                Tiêu đề: Mini App
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Không có
                Nội dung:
                Hoàn thành""");
        assertThat(captor.getValue().getPerformer()).isEqualTo("Nguyễn Văn A");
        assertThat(captor.getValue().getCollaborators()).isEqualTo("Không có");
    }

    @Test
    void shouldUseDeterministicQueriesForRecentAndDateSpecificPersonalReads() {
        DailyReport report = new DailyReport();
        LocalDate reportDate = LocalDate.of(2026, 6, 17);
        when(reportRepository.findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(
                12345L,
                org.springframework.data.domain.PageRequest.of(0, 5)
        )).thenReturn(List.of(report));
        when(reportRepository.findByUser_TelegramUserIdAndReportDateOrderByCreatedAtDescIdDesc(
                12345L,
                reportDate
        ))
                .thenReturn(List.of(report));

        assertThat(service.findRecentForTelegramUser(12345L, 5)).containsExactly(report);
        assertThat(service.findForTelegramUserOnDate(12345L, reportDate)).containsExactly(report);
        verify(reportRepository).findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(
                12345L,
                org.springframework.data.domain.PageRequest.of(0, 5)
        );
        verify(reportRepository).findByUser_TelegramUserIdAndReportDateOrderByCreatedAtDescIdDesc(
                12345L,
                reportDate
        );
    }

    private User activeUser(long id, long telegramUserId) {
        User user = new User();
        user.setId(id);
        user.setTelegramUserId(telegramUserId);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private void stubCurrentUser(User user) {
        when(userRepository.findInternalIdByTelegramUserId(user.getTelegramUserId()))
                .thenReturn(Optional.of(user.getId()));
        when(userRepository.findLockedById(user.getId())).thenReturn(Optional.of(user));
    }
}
