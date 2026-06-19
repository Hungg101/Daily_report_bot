package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyReportServiceTest {

    private final DailyReportRepository dailyReportRepository = mock(DailyReportRepository.class);
    private final TelegramUserRepository telegramUserRepository = mock(TelegramUserRepository.class);
    private final Clock reportClock = Clock.fixed(
            Instant.parse("2026-06-16T17:30:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh")
    );
    private final DailyReportService service = new DailyReportService(
            dailyReportRepository,
            telegramUserRepository,
            reportClock
    );

    @Test
    void shouldSaveDailyReport() {
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(12345L);

        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        DailyReportSubmissionStatus status = service.submitToday(12345L, "  Hoàn thành API báo cáo  ");

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(dailyReportRepository).save(captor.capture());

        DailyReport savedReport = captor.getValue();
        assertThat(status).isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(savedReport.getTelegramUser()).isSameAs(telegramUser);
        assertThat(savedReport.getReportDate()).isEqualTo(LocalDate.of(2026, 6, 17));
        assertThat(savedReport.getContent()).isEqualTo("Hoàn thành API báo cáo");
    }

    @Test
    void shouldRejectBlankContent() {
        DailyReportSubmissionStatus status = service.submitToday(12345L, "   ");

        assertThat(status).isEqualTo(DailyReportSubmissionStatus.BLANK_CONTENT);
        verifyNoInteractions(telegramUserRepository, dailyReportRepository);
    }

    @Test
    void shouldAllowMultipleReportsForToday() {
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(12345L);

        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        DailyReportSubmissionStatus firstStatus = service.submitToday(12345L, "Báo cáo lần 1");
        DailyReportSubmissionStatus secondStatus = service.submitToday(12345L, "Báo cáo lần 2");

        assertThat(firstStatus).isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(secondStatus).isEqualTo(DailyReportSubmissionStatus.SAVED);
        verify(dailyReportRepository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.any(DailyReport.class));
    }

    @Test
    void shouldFindLatestReportForTelegramUser() {
        DailyReport dailyReport = new DailyReport();
        when(dailyReportRepository.findFirstByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(12345L))
                .thenReturn(Optional.of(dailyReport));

        Optional<DailyReport> latestReport = service.findLatestForTelegramUser(12345L);

        assertThat(latestReport).containsSame(dailyReport);
        verify(dailyReportRepository).findFirstByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(12345L);
    }

    @Test
    void shouldFindRecentReportsForTelegramUser() {
        DailyReport firstReport = new DailyReport();
        DailyReport secondReport = new DailyReport();
        when(dailyReportRepository.findByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(12345L, PageRequest.of(0, 2)))
                .thenReturn(List.of(firstReport, secondReport));

        List<DailyReport> reports = service.findRecentForTelegramUser(12345L, 2);

        assertThat(reports).containsExactly(firstReport, secondReport);
        verify(dailyReportRepository)
                .findByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(12345L, PageRequest.of(0, 2));
    }

    @Test
    void shouldReturnEmptyRecentReportsWhenTelegramUserIdIsMissing() {
        List<DailyReport> reports = service.findRecentForTelegramUser(null, 5);

        assertThat(reports).isEmpty();
        verifyNoInteractions(dailyReportRepository, telegramUserRepository);
    }

    @Test
    void shouldReturnEmptyRecentReportsWhenLimitIsInvalid() {
        List<DailyReport> reports = service.findRecentForTelegramUser(12345L, 0);

        assertThat(reports).isEmpty();
        verifyNoInteractions(dailyReportRepository, telegramUserRepository);
    }

    @Test
    void shouldReturnEmptyLatestReportWhenTelegramUserIdIsMissing() {
        Optional<DailyReport> latestReport = service.findLatestForTelegramUser(null);

        assertThat(latestReport).isEmpty();
        verifyNoInteractions(dailyReportRepository, telegramUserRepository);
    }
}
