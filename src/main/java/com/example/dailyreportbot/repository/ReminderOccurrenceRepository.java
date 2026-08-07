package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.ReminderOccurrence;
import com.example.dailyreportbot.entity.ReminderOccurrenceState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReminderOccurrenceRepository extends JpaRepository<ReminderOccurrence, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT occurrence
            FROM ReminderOccurrence occurrence
            WHERE occurrence.user.id = :userId
              AND occurrence.businessDate = :businessDate
            """)
    Optional<ReminderOccurrence> findLockedByUserIdAndBusinessDate(
            @Param("userId") long userId,
            @Param("businessDate") LocalDate businessDate
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT occurrence FROM ReminderOccurrence occurrence WHERE occurrence.id = :id")
    Optional<ReminderOccurrence> findLockedById(@Param("id") long id);

    List<ReminderOccurrence> findByBusinessDateAndStateOrderByIdAsc(
            LocalDate businessDate,
            ReminderOccurrenceState state
    );
}
