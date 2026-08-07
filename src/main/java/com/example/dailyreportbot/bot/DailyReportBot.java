package com.example.dailyreportbot.bot;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.TelegramCommandService;
import com.example.dailyreportbot.service.UserRegistrationService;
import com.example.dailyreportbot.service.UserRegistrationStatus;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Component
/**
 * Lớp điều khiển chính của Bot Telegram.
 * Đóng vai trò là điểm neo tiếp nhận các tin nhắn (Webhook/Long Polling)
 * từ nền tảng Telegram và điều hướng đến các Service xử lý tương ứng.
 */
public class DailyReportBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(DailyReportBot.class);
    private static final String PHONE_CONSENT_MESSAGE =
            "Để tạo tài khoản, bot cần số điện thoại của bạn. "
                    + "Hãy bấm nút bên dưới nếu bạn đồng ý chia sẻ số điện thoại Telegram của chính mình.";
    private static final String PRIVATE_CHAT_REQUIRED_MESSAGE =
            "Vui lòng mở chat riêng với bot và gửi /start để xác nhận số điện thoại.";
    private static final String PHONE_CONFIRMED_MESSAGE =
            "Đã xác nhận số điện thoại. Bạn có thể dùng các lệnh của bot.";
    private static final String PHONE_REJECTED_MESSAGE =
            "Không thể xác nhận số điện thoại này. Hãy dùng nút bên dưới để chia sẻ contact của chính bạn.";
    private static final String PHONE_CONSENT_BUTTON = "Đồng ý chia sẻ số điện thoại";
    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final String TRUNCATION_NOTICE =
            "\n\n… Nội dung đã được rút gọn vì vượt giới hạn Telegram.";

    private final TelegramBotProperties properties;
    private final TelegramCommandService commandService;
    private final UserRegistrationService userRegistrationService;

    public DailyReportBot(
            TelegramBotProperties properties,
            TelegramCommandService commandService,
            UserRegistrationService userRegistrationService
    ) {
        super(requireText(
                properties == null ? null : properties.getToken(),
                "Missing telegram.bot.token or TELEGRAM_BOT_TOKEN."
        ));
        requireText(properties.getUsername(), "Missing telegram.bot.username or TELEGRAM_BOT_USERNAME.");
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
        if (message.hasContact()) {
            handleSharedContact(message);
            return;
        }

        Long privateChatId = message.isUserMessage() ? message.getChatId() : null;
        UserRegistrationStatus registrationStatus = registerUser(message.getFrom(), privateChatId);
        if (registrationStatus == UserRegistrationStatus.PHONE_REQUIRED) {
            sendPhoneConsent(message.getChatId(), message.isUserMessage(), PHONE_CONSENT_MESSAGE);
            return;
        }

        if (!message.hasText()) {
            return;
        }

        logIncomingMessage(message);

        commandService.createResponse(message)
                .ifPresent(response -> sendResponse(message.getChatId(), response));
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        if (callbackQuery == null) {
            return;
        }

        Long chatId = resolveCallbackChatId(callbackQuery);
        boolean privateChat = callbackQuery.getMessage() instanceof Message message && message.isUserMessage();
        UserRegistrationStatus registrationStatus = registerUser(
                callbackQuery.getFrom(),
                privateChat ? chatId : null
        );
        answerCallbackQuery(callbackQuery);
        if (registrationStatus == UserRegistrationStatus.PHONE_REQUIRED) {
            sendPhoneConsent(chatId, privateChat, PHONE_CONSENT_MESSAGE);
            return;
        }

        commandService.createResponse(callbackQuery)
                .ifPresent(response -> sendResponse(chatId, response));
    }

    private UserRegistrationStatus registerUser(User user, Long chatId) {
        try {
            return userRegistrationService.registerOrUpdate(user, chatId);
        } catch (RuntimeException exception) {
            Long userId = user != null ? user.getId() : null;
            log.error(
                    "Cannot register Telegram user - telegramUserId={}, errorType={}",
                    userId,
                    exception.getClass().getSimpleName()
            );
            return UserRegistrationStatus.CONFLICT;
        }
    }

    private void handleSharedContact(Message message) {
        if (!message.isUserMessage()) {
            sendPhoneConsent(message.getChatId(), false, PHONE_REJECTED_MESSAGE);
            return;
        }

        UserRegistrationStatus status;
        try {
            status = userRegistrationService.registerWithOwnPhoneNumber(
                    message.getFrom(),
                    message.getChatId(),
                    message.getContact()
            );
        } catch (RuntimeException exception) {
            User sender = message.getFrom();
            Long telegramUserId = sender != null ? sender.getId() : null;
            log.error(
                    "Cannot save shared Telegram contact - telegramUserId={}, errorType={}",
                    telegramUserId,
                    exception.getClass().getSimpleName()
            );
            status = UserRegistrationStatus.CONFLICT;
        }

        if (status == UserRegistrationStatus.REGISTERED
                || status == UserRegistrationStatus.REFRESHED
                || status == UserRegistrationStatus.NO_CHANGE) {
            SendMessage response = new SendMessage(message.getChatId().toString(), PHONE_CONFIRMED_MESSAGE);
            response.setReplyMarkup(new ReplyKeyboardRemove(true));
            sendResponse(message.getChatId(), response);
            return;
        }

        sendPhoneConsent(message.getChatId(), message.isUserMessage(), PHONE_REJECTED_MESSAGE);
    }

    private void sendPhoneConsent(Long chatId, boolean privateChat, String messageText) {
        if (chatId == null) {
            return;
        }

        SendMessage response = new SendMessage(
                chatId.toString(),
                privateChat ? messageText : PRIVATE_CHAT_REQUIRED_MESSAGE
        );
        if (privateChat) {
            KeyboardButton button = new KeyboardButton(PHONE_CONSENT_BUTTON);
            button.setRequestContact(true);
            KeyboardRow row = new KeyboardRow();
            row.add(button);
            ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup(List.of(row));
            keyboard.setResizeKeyboard(true);
            keyboard.setOneTimeKeyboard(true);
            response.setReplyMarkup(keyboard);
        }
        sendResponse(chatId, response);
    }

    private void sendResponse(Long chatId, SendMessage response) {
        try {
            response.setText(limitTelegramText(response.getText()));
            execute(response);
        } catch (TelegramApiException exception) {
            log.error(
                    "Cannot send Telegram response - errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private String limitTelegramText(String text) {
        if (text == null || text.length() <= TELEGRAM_TEXT_LIMIT) {
            return text;
        }

        int cut = TELEGRAM_TEXT_LIMIT - TRUNCATION_NOTICE.length();
        if (Character.isHighSurrogate(text.charAt(cut - 1))
                && Character.isLowSurrogate(text.charAt(cut))) {
            cut -= 1;
        }
        return text.substring(0, cut) + TRUNCATION_NOTICE;
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
            log.error(
                    "Cannot answer Telegram callback - errorType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void logIncomingMessage(Message message) {
        User user = message.getFrom();
        Long userId = user != null ? user.getId() : null;
        String text = message.getText();

        log.info(
                "Telegram text message received - userId={}, textLength={}",
                userId,
                text == null ? 0 : text.length()
        );
    }

    private Long resolveCallbackChatId(CallbackQuery callbackQuery) {
        return callbackQuery.getMessage() != null ? callbackQuery.getMessage().getChatId() : null;
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
