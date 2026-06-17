package com.example.dailyreportbot.bot;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.TelegramCommandService;
import com.example.dailyreportbot.service.TelegramUserRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
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
        if (update == null) {
            return;
        }

        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }

        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        registerTelegramUser(message);

        if (!message.hasText()) {
            return;
        }

        logIncomingMessage(message);

        commandService.createResponse(message)
                .ifPresent(response -> sendResponse(message, response));
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        if (callbackQuery == null) {
            return;
        }

        registerTelegramUser(callbackQuery.getFrom());
        answerCallbackQuery(callbackQuery);

        commandService.createResponse(callbackQuery)
                .ifPresent(response -> sendResponse(resolveCallbackChatId(callbackQuery), response));
    }

    private void registerTelegramUser(Message message) {
        registerTelegramUser(message.getFrom());
    }

    private void registerTelegramUser(User user) {
        try {
            userRegistrationService.registerIfNew(user);
        } catch (RuntimeException exception) {
            Long userId = user != null ? user.getId() : null;
            String username = user != null ? user.getUserName() : null;
            log.error("Cannot register Telegram user - userId={}, username={}", userId, username, exception);
        }
    }

    private void sendResponse(Message message, SendMessage response) {
        sendResponse(message.getChatId(), response);
    }

    private void sendResponse(Long chatId, SendMessage response) {
        try {
            execute(response);
        } catch (TelegramApiException exception) {
            log.error("Cannot send Telegram response to chatId={}", chatId, exception);
        }
    }

    private void answerCallbackQuery(CallbackQuery callbackQuery) {
        if (!StringUtils.hasText(callbackQuery.getId())) {
            return;
        }

        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQuery.getId());
        try {
            execute(answer);
        } catch (TelegramApiException exception) {
            log.error("Cannot answer Telegram callbackQueryId={}", callbackQuery.getId(), exception);
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

        return null;
    }

    private Long resolveCallbackChatId(CallbackQuery callbackQuery) {
        return callbackQuery.getMessage() != null ? callbackQuery.getMessage().getChatId() : null;
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
