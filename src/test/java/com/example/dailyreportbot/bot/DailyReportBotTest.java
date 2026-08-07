package com.example.dailyreportbot.bot;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.TelegramCommandService;
import com.example.dailyreportbot.service.UserRegistrationService;
import com.example.dailyreportbot.service.UserRegistrationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.LoggerFactory.getLogger;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyReportBotTest {

    private TelegramCommandService commandService;
    private UserRegistrationService userRegistrationService;
    private CapturingDailyReportBot bot;

    @BeforeEach
    void setUp() {
        commandService = mock(TelegramCommandService.class);
        userRegistrationService = mock(UserRegistrationService.class);
        bot = new CapturingDailyReportBot(createProperties(), commandService, userRegistrationService);
    }

    @Test
    void shouldIgnoreNullUpdate() {
        bot.onUpdateReceived(null);

        verifyNoInteractions(userRegistrationService, commandService);
        assertThat(bot.sentResponse).isNull();
    }

    @Test
    void shouldIgnoreUpdateWithoutMessage() {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(false);

        bot.onUpdateReceived(update);

        verifyNoInteractions(userRegistrationService, commandService);
        assertThat(bot.sentResponse).isNull();
    }

    @Test
    void shouldAskFirstTimePrivateUserForPhoneConsent() {
        User user = telegramUser();
        Message message = textMessage(user, "/start");
        when(message.isUserMessage()).thenReturn(true);
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.PHONE_REQUIRED);

        bot.onUpdateReceived(createUpdate(message));

        assertThat(bot.sentResponse.getText()).contains("số điện thoại");
        assertThat(bot.sentResponse.getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);
        ReplyKeyboardMarkup keyboard = (ReplyKeyboardMarkup) bot.sentResponse.getReplyMarkup();
        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(keyboard.getKeyboard().get(0).get(0).getText())
                .isEqualTo("Đồng ý chia sẻ số điện thoại");
        assertThat(keyboard.getKeyboard().get(0).get(0).getRequestContact()).isTrue();
        verifyNoInteractions(commandService);
    }

    @Test
    void shouldNotShowContactButtonOutsidePrivateChat() {
        User user = telegramUser();
        Message message = textMessage(user, "/start");
        when(message.isUserMessage()).thenReturn(false);
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.PHONE_REQUIRED);
        when(userRegistrationService.registerOrUpdate(user, null))
                .thenReturn(UserRegistrationStatus.PHONE_REQUIRED);

        bot.onUpdateReceived(createUpdate(message));

        assertThat(bot.sentResponse.getText()).contains("chat riêng");
        assertThat(bot.sentResponse.getReplyMarkup()).isNull();
        verify(userRegistrationService).registerOrUpdate(user, null);
    }

    @Test
    void shouldRegisterOwnContactAndRemoveConsentKeyboard() {
        User user = telegramUser();
        Contact contact = mock(Contact.class);
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(user);
        when(message.getChatId()).thenReturn(1001L);
        when(message.hasContact()).thenReturn(true);
        when(message.getContact()).thenReturn(contact);
        when(message.isUserMessage()).thenReturn(true);
        when(userRegistrationService.registerWithOwnPhoneNumber(user, 1001L, contact))
                .thenReturn(UserRegistrationStatus.REGISTERED);

        bot.onUpdateReceived(createUpdate(message));

        verify(userRegistrationService).registerWithOwnPhoneNumber(user, 1001L, contact);
        assertThat(bot.sentResponse.getText()).contains("Đã xác nhận số điện thoại");
        assertThat(bot.sentResponse.getReplyMarkup()).isInstanceOf(ReplyKeyboardRemove.class);
        verifyNoInteractions(commandService);
    }

    @Test
    void shouldRejectSharedContactOutsidePrivateChatWithoutRegistrationMutation() {
        User user = telegramUser();
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);
        when(message.getFrom()).thenReturn(user);
        when(message.hasContact()).thenReturn(true);
        when(message.getContact()).thenReturn(mock(Contact.class));
        when(message.isUserMessage()).thenReturn(false);

        bot.onUpdateReceived(createUpdate(message));

        verifyNoInteractions(userRegistrationService, commandService);
        assertThat(bot.sentResponse.getText()).contains("chat riêng");
        assertThat(bot.sentResponse.getReplyMarkup()).isNull();
    }

    @Test
    void shouldRegisterExistingUserAndIgnoreNonTextMessage() {
        User user = telegramUser();
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(user);
        when(message.getChatId()).thenReturn(1001L);
        when(message.isUserMessage()).thenReturn(true);
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);

        bot.onUpdateReceived(createUpdate(message));

        verify(userRegistrationService).registerOrUpdate(user, 1001L);
        verifyNoInteractions(commandService);
        assertThat(bot.sentResponse).isNull();
    }

    @Test
    void shouldCreateAndSendNormalResponseForRegisteredUser() {
        User user = telegramUser();
        Message message = textMessage(user, "/start");
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        SendMessage response = new SendMessage("1001", "Xin chào");
        when(commandService.createResponse(message)).thenReturn(Optional.of(response));

        bot.onUpdateReceived(createUpdate(message));

        verify(commandService).createResponse(message);
        assertThat(bot.sentResponse).isSameAs(response);
    }

    @Test
    void shouldLeaveTelegramResponseAtTheLimitUnchanged() {
        User user = telegramUser();
        Message message = textMessage(user, "/status");
        String text = "a".repeat(4096);
        SendMessage response = new SendMessage("1001", text);
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        when(commandService.createResponse(message)).thenReturn(Optional.of(response));

        bot.onUpdateReceived(createUpdate(message));

        assertThat(bot.sentResponse.getText()).isEqualTo(text);
    }

    @Test
    void shouldShortenOversizedTelegramResponseWithoutSplittingSurrogatePair() {
        String notice = "\n\n… Nội dung đã được rút gọn vì vượt giới hạn Telegram.";
        int safeCut = 4096 - notice.length();
        String expected = "a".repeat(safeCut - 1) + notice;
        String oversized = "a".repeat(safeCut - 1) + "\uD83D\uDE00" + "b".repeat(4096);
        User user = telegramUser();
        Message message = textMessage(user, "/status");
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        when(commandService.createResponse(message))
                .thenReturn(Optional.of(new SendMessage("1001", oversized)));

        bot.onUpdateReceived(createUpdate(message));

        assertThat(bot.sentResponse.getText())
                .hasSizeLessThanOrEqualTo(4096)
                .isEqualTo(expected)
                .endsWith(notice);
    }

    @Test
    void shouldLogIncomingTextMetadataWithoutSensitiveValuesOrChatId() {
        List<String> sensitiveValues = List.of(
                "confidential wizard title",
                "confidential collaborators",
                "confidential report body"
        );
        String username = "private_username";
        User user = telegramUser();
        when(user.getUserName()).thenReturn(username);
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        List<Message> messages = sensitiveValues.stream()
                .map(value -> textMessage(user, value))
                .toList();
        messages.forEach(message -> when(commandService.createResponse(message))
                .thenReturn(Optional.of(new SendMessage("1001", "Next"))));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(DailyReportBot.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            messages.forEach(message -> bot.onUpdateReceived(createUpdate(message)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(3)
                .allSatisfy(logMessage -> {
                    assertThat(logMessage)
                            .contains("userId=12345", "textLength=")
                            .doesNotContain("chatId=", "1001");
                    sensitiveValues.forEach(value -> assertThat(logMessage).doesNotContain(value));
                    assertThat(logMessage).doesNotContain(username);
                });
    }

    @Test
    void shouldAnswerCallbackAndSendGeneratedResponseForRegisteredUser() {
        User user = telegramUser();
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);
        when(message.isUserMessage()).thenReturn(true);
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(callbackQuery.getId()).thenReturn("callback-1");
        when(callbackQuery.getFrom()).thenReturn(user);
        when(callbackQuery.getMessage()).thenReturn(message);
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        SendMessage response = new SendMessage("1001", "Vui lòng nhập nội dung báo cáo hôm nay.");
        when(commandService.createResponse(callbackQuery)).thenReturn(Optional.of(response));
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);

        bot.onUpdateReceived(update);

        verify(commandService).createResponse(callbackQuery);
        assertThat(bot.answeredCallbackQuery.getCallbackQueryId()).isEqualTo("callback-1");
        assertThat(bot.sentResponse).isSameAs(response);
    }

    @Test
    void shouldNotUseGroupCallbackChatAsPrivateDeliveryDestination() {
        User user = telegramUser();
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);
        when(message.isUserMessage()).thenReturn(false);
        CallbackQuery callbackQuery = callbackQuery(user, message);
        when(userRegistrationService.registerOrUpdate(user, null))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        when(commandService.createResponse(callbackQuery))
                .thenReturn(Optional.of(new SendMessage("1001", "Guidance")));

        bot.onUpdateReceived(createCallbackUpdate(callbackQuery));

        verify(userRegistrationService).registerOrUpdate(user, null);
        verify(commandService).createResponse(callbackQuery);
    }

    @Test
    void shouldPreservePrivateDeliveryDestinationForInaccessibleCallbackMessage() {
        User user = telegramUser();
        MaybeInaccessibleMessage message = mock(MaybeInaccessibleMessage.class);
        when(message.getChatId()).thenReturn(1001L);
        CallbackQuery callbackQuery = callbackQuery(user, message);
        when(userRegistrationService.registerOrUpdate(user, null))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        when(commandService.createResponse(callbackQuery))
                .thenReturn(Optional.of(new SendMessage("1001", "Guidance")));

        bot.onUpdateReceived(createCallbackUpdate(callbackQuery));

        verify(userRegistrationService).registerOrUpdate(user, null);
        verify(commandService).createResponse(callbackQuery);
    }

    @Test
    void shouldRedactTelegramTransportFailureLogs() {
        User user = telegramUser();
        Message message = textMessage(user, "/manage");
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenReturn(UserRegistrationStatus.NO_CHANGE);
        when(commandService.createResponse(message))
                .thenReturn(Optional.of(new SendMessage("1001", "Management response")));

        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getChatId()).thenReturn(1001L);
        when(callbackMessage.isUserMessage()).thenReturn(true);
        CallbackQuery callbackQuery = callbackQuery(user, callbackMessage);
        when(commandService.createResponse(callbackQuery))
                .thenReturn(Optional.of(new SendMessage("1001", "Callback response")));

        String sendDetail = "PRIVATE_SEND_DETAIL_51a9";
        String callbackDetail = "PRIVATE_CALLBACK_DETAIL_72bc";
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(DailyReportBot.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            bot.executeFailure = new TelegramApiException(sendDetail);
            bot.onUpdateReceived(createUpdate(message));
            bot.executeFailure = new TelegramApiException(callbackDetail);
            bot.onUpdateReceived(createCallbackUpdate(callbackQuery));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> failures = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Cannot send Telegram response")
                        || event.getFormattedMessage().startsWith("Cannot answer Telegram callback"))
                .toList();
        assertThat(failures).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Cannot send Telegram response - errorType=TelegramApiException",
                "Cannot answer Telegram callback - errorType=TelegramApiException"
        ).allSatisfy(logMessage -> assertThat(logMessage)
                .doesNotContain("1001", "callback-1", sendDetail, callbackDetail));
        assertThat(failures).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private User telegramUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);
        when(user.getUserName()).thenReturn("daily_user");
        when(user.getFirstName()).thenReturn("An");
        return user;
    }

    private Message textMessage(User user, String text) {
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(user);
        when(message.hasText()).thenReturn(true);
        when(message.getChatId()).thenReturn(1001L);
        when(message.getText()).thenReturn(text);
        when(message.isUserMessage()).thenReturn(true);
        return message;
    }

    private CallbackQuery callbackQuery(User user, MaybeInaccessibleMessage message) {
        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(callbackQuery.getId()).thenReturn("callback-1");
        when(callbackQuery.getFrom()).thenReturn(user);
        when(callbackQuery.getMessage()).thenReturn(message);
        return callbackQuery;
    }

    private Update createCallbackUpdate(CallbackQuery callbackQuery) {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        return update;
    }

    private Update createUpdate(Message message) {
        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        return update;
    }

    private TelegramBotProperties createProperties() {
        TelegramBotProperties properties = new TelegramBotProperties();
        properties.setUsername("daily_report_bot");
        properties.setToken("123456789:test-token");
        return properties;
    }

    private static class CapturingDailyReportBot extends DailyReportBot {

        private SendMessage sentResponse;
        private AnswerCallbackQuery answeredCallbackQuery;
        private TelegramApiException executeFailure;

        private CapturingDailyReportBot(
                TelegramBotProperties properties,
                TelegramCommandService commandService,
                UserRegistrationService userRegistrationService
        ) {
            super(properties, commandService, userRegistrationService);
        }

        @Override
        public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method)
                throws TelegramApiException {
            if (executeFailure != null) {
                TelegramApiException failure = executeFailure;
                executeFailure = null;
                throw failure;
            }
            if (method instanceof SendMessage sendMessage) {
                sentResponse = sendMessage;
            }
            if (method instanceof AnswerCallbackQuery answerCallbackQuery) {
                answeredCallbackQuery = answerCallbackQuery;
            }
            return null;
        }
    }

    @Test
    void shouldNotLogTelegramUsernameWhenRegistrationFails() {
        String username = "private_registration_username";
        String exceptionDetail = "PRIVATE_REGISTRATION_DETAIL_38de";
        User user = telegramUser();
        when(user.getUserName()).thenReturn(username);
        Message message = textMessage(user, "/teamstatus");
        when(userRegistrationService.registerOrUpdate(user, 1001L))
                .thenThrow(new RuntimeException(exceptionDetail));
        when(commandService.createResponse(message)).thenReturn(Optional.empty());

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(DailyReportBot.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            bot.onUpdateReceived(createUpdate(message));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .filteredOn(event -> event.getFormattedMessage().startsWith("Cannot register Telegram user"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("telegramUserId=12345", "errorType=RuntimeException")
                            .doesNotContain(username, "username=", exceptionDetail);
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }
}
