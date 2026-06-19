package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    private final DailyReportRepository dailyReportRepository;
    private final TelegramUserRepository telegramUserRepository;
    private final Clock reportClock;

    public DailyReportService(
            DailyReportRepository dailyReportRepository,
            TelegramUserRepository telegramUserRepository,
            Clock reportClock
    ) {
        this.dailyReportRepository = dailyReportRepository;
        this.telegramUserRepository = telegramUserRepository;
        this.reportClock = reportClock;
    }

    @Transactional
    public DailyReportSubmissionStatus submitToday(Long telegramUserId, String content) {
        if (!StringUtils.hasText(content)) {
            return DailyReportSubmissionStatus.BLANK_CONTENT;
        }

        return telegramUserRepository.findByTelegramUserId(telegramUserId)
                .map(telegramUser -> saveReport(telegramUser, content.trim()))
                .orElse(DailyReportSubmissionStatus.TELEGRAM_USER_NOT_FOUND);
    }

    @Transactional(readOnly = true)
    public Optional<DailyReport> findLatestForTelegramUser(Long telegramUserId) {
        if (telegramUserId == null) {
            return Optional.empty();
        }

        return dailyReportRepository.findFirstByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(telegramUserId);
    }

    @Transactional(readOnly = true)
    public List<DailyReport> findRecentForTelegramUser(Long telegramUserId, int limit) {
        if (telegramUserId == null || limit <= 0) {
            return List.of();
        }

        return dailyReportRepository.findByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(
                telegramUserId,
                PageRequest.of(0, limit)
        );
    }

    private DailyReportSubmissionStatus saveReport(TelegramUser telegramUser, String content) {
        DailyReport dailyReport = new DailyReport();
        dailyReport.setTelegramUser(telegramUser);
        dailyReport.setReportDate(LocalDate.now(reportClock));
        dailyReport.setContent(content);

        DailyReport savedReport = dailyReportRepository.save(dailyReport);
        Long reportId = savedReport != null ? savedReport.getId() : dailyReport.getId();
        log.info(
                "Daily report saved - reportId={}, telegramUserId={}, reportDate={}",
                reportId,
                telegramUser.getTelegramUserId(),
                dailyReport.getReportDate()
        );
        return DailyReportSubmissionStatus.SAVED;
    }
}
