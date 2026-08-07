package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT report FROM DailyReport report JOIN FETCH report.user WHERE report.id = :id")
    Optional<DailyReport> findLockedById(@Param("id") Long id);

    List<DailyReport> findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(
            Long telegramUserId,
            Pageable pageable
    );

    List<DailyReport> findByUser_TelegramUserIdAndReportDateOrderByCreatedAtDescIdDesc(
            Long telegramUserId,
            LocalDate reportDate
    );

    List<DailyReport> findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(
            Long userId,
            LocalDate reportDate
    );

    List<DailyReport> findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
            Collection<Long> userIds,
            LocalDate reportDate
    );
}
