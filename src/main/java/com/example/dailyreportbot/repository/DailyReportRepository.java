package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.TelegramUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {

    boolean existsByTelegramUserAndReportDate(TelegramUser telegramUser, LocalDate reportDate);
}
