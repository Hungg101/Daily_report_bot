package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {

    Optional<DailyReport> findFirstByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(Long telegramUserId);

    List<DailyReport> findByTelegramUser_TelegramUserIdOrderByCreatedAtDesc(Long telegramUserId, Pageable pageable);

    List<DailyReport> findByTelegramUser_TelegramUserIdAndReportDateOrderByCreatedAtDesc(
            Long telegramUserId,
            LocalDate reportDate
    );

    List<DailyReport> findByTelegramUser_Employee_IdAndReportDateOrderByCreatedAtDesc(
            Long employeeId,
            LocalDate reportDate
    );

    List<DailyReport> findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
            Collection<Long> employeeIds,
            LocalDate reportDate
    );
}
