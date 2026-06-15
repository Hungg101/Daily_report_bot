package com.example.dailyreportbot.bot;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.TelegramCommandService;
import com.example.dailyreportbot.service.TelegramUserRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Component
public class DailyReportBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(DailyReportBot.class);

    private final TelegramBotProperties properties;
    private final TelegramCommandService commandService;
    private final TelegramUserRegistrationService userRegistrationService;

    public DailyReportBot(
            TelegramBotProperties properties,
            TelegramCommandService commandService,
            TelegramUserRegistrationService userRegistrationService
    ) {
        super(requireToken(properties));
        requireUsername(properties);
        this.properties = properties;
        this.commandService = commandService;
        this.userRegistrationService = userRegistrationService;
    }

    @Override
    public String getBotUsername() {
        return properties.getUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        userRegistrationService.registerIfNew(message.getFrom());

        if (!message.hasText() && message.getWebAppData() == null) {
            return;
        }

        logIncomingMessage(message);

        SendMessage response = commandService.createResponse(message);
        try {
            execute(response);
        } catch (TelegramApiException exception) {
            log.error("Cannot send Telegram response to chatId={}", message.getChatId(), exception);
        }
    }

    private void logIncomingMessage(Message message) {
        User user = message.getFrom();
        Long userId = user != null ? user.getId() : null;
        String username = user != null ? user.getUserName() : null;

        log.info(
                "Telegram message received - userId={}, username={}, chatId={}, text={}",
                userId,
                username,
                message.getChatId(),
                resolveMessageContent(message)
        );
    }

    private String resolveMessageContent(Message message) {
        if (message.hasText()) {
            return message.getText();
        }

        if (message.getWebAppData() != null) {
            return message.getWebAppData().getData();
        }

        return null;
    }

    private static String requireToken(TelegramBotProperties properties) {
        if (properties == null || !StringUtils.hasText(properties.getToken())) {
            throw new IllegalStateException("Missing telegram.bot.token or TELEGRAM_BOT_TOKEN.");
        }
        return properties.getToken();
    }

    private static void requireUsername(TelegramBotProperties properties) {
        if (properties == null || !StringUtils.hasText(properties.getUsername())) {
            throw new IllegalStateException("Missing telegram.bot.username or TELEGRAM_BOT_USERNAME.");
        }
    }
}
