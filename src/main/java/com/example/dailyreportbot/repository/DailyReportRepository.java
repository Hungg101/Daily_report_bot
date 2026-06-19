package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {

    Optional<DailyReport> findFirstByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(Long telegramUserId);

    List<DailyReport> findByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(Long telegramUserId, Pageable pageable);
}
