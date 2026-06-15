package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;

@Service
public class TelegramUserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramUserRegistrationService.class);

    private final TelegramUserRepository telegramUserRepository;

    public TelegramUserRegistrationService(TelegramUserRepository telegramUserRepository) {
        this.telegramUserRepository = telegramUserRepository;
    }

    @Transactional
    public void registerIfNew(User user) {
        if (user == null || user.getId() == null) {
            return;
        }

        Long telegramUserId = user.getId();
        if (telegramUserRepository.existsByTelegramUserId(telegramUserId)) {
            return;
        }

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(telegramUserId);
        telegramUser.setUsername(user.getUserName());
        telegramUser.setFirstName(user.getFirstName());

        telegramUserRepository.save(telegramUser);
        log.info(
                "New Telegram user registered:\ntelegramUserId={}\nusername={}",
                telegramUserId,
                user.getUserName()
        );
    }
}
