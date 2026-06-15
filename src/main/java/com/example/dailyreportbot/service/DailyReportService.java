package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

@Service
public class DailyReportService {

    private final DailyReportRepository dailyReportRepository;
    private final TelegramUserRepository telegramUserRepository;

    public DailyReportService(
            DailyReportRepository dailyReportRepository,
            TelegramUserRepository telegramUserRepository
    ) {
        this.dailyReportRepository = dailyReportRepository;
        this.telegramUserRepository = telegramUserRepository;
    }

    @Transactional(readOnly = true)
    public boolean hasSubmittedToday(Long telegramUserId) {
        return telegramUserRepository.findByTelegramUserId(telegramUserId)
                .map(telegramUser -> dailyReportRepository.existsByTelegramUserAndReportDate(
                        telegramUser,
                        LocalDate.now()
                ))
                .orElse(false);
    }

    @Transactional
    public DailyReportSubmissionStatus submitToday(Long telegramUserId, String content) {
        if (!StringUtils.hasText(content)) {
            return DailyReportSubmissionStatus.BLANK_CONTENT;
        }

        return telegramUserRepository.findByTelegramUserId(telegramUserId)
                .map(telegramUser -> saveReportIfAllowed(telegramUser, content.trim()))
                .orElse(DailyReportSubmissionStatus.TELEGRAM_USER_NOT_FOUND);
    }

    private DailyReportSubmissionStatus saveReportIfAllowed(TelegramUser telegramUser, String content) {
        LocalDate today = LocalDate.now();
        if (dailyReportRepository.existsByTelegramUserAndReportDate(telegramUser, today)) {
            return DailyReportSubmissionStatus.ALREADY_SUBMITTED;
        }

        DailyReport dailyReport = new DailyReport();
        dailyReport.setTelegramUser(telegramUser);
        dailyReport.setReportDate(today);
        dailyReport.setContent(content);

        dailyReportRepository.save(dailyReport);
        return DailyReportSubmissionStatus.SAVED;
    }
}
