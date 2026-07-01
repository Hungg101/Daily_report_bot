package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.TelegramUser;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramUserRepository extends JpaRepository<TelegramUser, Long> {

    boolean existsByTelegramUserId(Long telegramUserId);

    @EntityGraph(attributePaths = "employee")
    Optional<TelegramUser> findByTelegramUserId(Long telegramUserId);

    Optional<TelegramUser> findByEmployee_Id(Long employeeId);
}
