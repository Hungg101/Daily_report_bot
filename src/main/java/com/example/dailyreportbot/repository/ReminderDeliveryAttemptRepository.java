package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.ReminderDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReminderDeliveryAttemptRepository extends JpaRepository<ReminderDeliveryAttempt, Long> {

    Optional<ReminderDeliveryAttempt> findByOccurrence_IdAndAttemptNumber(long occurrenceId, int attemptNumber);
}
