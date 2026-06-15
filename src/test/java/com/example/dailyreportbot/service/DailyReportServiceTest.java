package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyReportServiceTest {

    private final DailyReportRepository dailyReportRepository = mock(DailyReportRepository.class);
    private final TelegramUserRepository telegramUserRepository = mock(TelegramUserRepository.class);
    private final DailyReportService service = new DailyReportService(dailyReportRepository, telegramUserRepository);

    @Test
    void shouldSaveDailyReport() {
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(12345L);

        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(dailyReportRepository.existsByTelegramUserAndReportDate(telegramUser, LocalDate.now()))
                .thenReturn(false);

        DailyReportSubmissionStatus status = service.submitToday(12345L, "  Hoàn thành API báo cáo  ");

        ArgumentCaptor<DailyReport> captor = ArgumentCaptor.forClass(DailyReport.class);
        verify(dailyReportRepository).save(captor.capture());

        DailyReport savedReport = captor.getValue();
        assertThat(status).isEqualTo(DailyReportSubmissionStatus.SAVED);
        assertThat(savedReport.getTelegramUser()).isSameAs(telegramUser);
        assertThat(savedReport.getReportDate()).isEqualTo(LocalDate.now());
        assertThat(savedReport.getContent()).isEqualTo("Hoàn thành API báo cáo");
    }

    @Test
    void shouldRejectBlankContent() {
        DailyReportSubmissionStatus status = service.submitToday(12345L, "   ");

        assertThat(status).isEqualTo(DailyReportSubmissionStatus.BLANK_CONTENT);
        verifyNoInteractions(telegramUserRepository, dailyReportRepository);
    }

    @Test
    void shouldRejectDuplicateReportForToday() {
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(12345L);

        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(dailyReportRepository.existsByTelegramUserAndReportDate(telegramUser, LocalDate.now()))
                .thenReturn(true);

        DailyReportSubmissionStatus status = service.submitToday(12345L, "Hoàn thành API báo cáo");

        assertThat(status).isEqualTo(DailyReportSubmissionStatus.ALREADY_SUBMITTED);
        verify(dailyReportRepository, never()).save(org.mockito.ArgumentMatchers.any(DailyReport.class));
    }
}
