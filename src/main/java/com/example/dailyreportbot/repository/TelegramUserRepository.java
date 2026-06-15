package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.TelegramUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramUserRepository extends JpaRepository<TelegramUser, Long> {

    boolean existsByTelegramUserId(Long telegramUserId);

    Optional<TelegramUser> findByTelegramUserId(Long telegramUserId);
}
