package com.example.dailyreportbot.bot;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.TelegramCommandService;
import com.example.dailyreportbot.service.TelegramUserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.Serializable;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DailyReportBotTest {

    private TelegramCommandService commandService;
    private TelegramUserRegistrationService userRegistrationService;
    private CapturingDailyReportBot bot;

    @BeforeEach
    void setUp() {
        commandService = mock(TelegramCommandService.class);
        userRegistrationService = mock(TelegramUserRegistrationService.class);
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
    void shouldRegisterUserAndIgnoreNonTextMessage() {
        User user = mock(User.class);
        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(user);
        when(message.hasText()).thenReturn(false);

        bot.onUpdateReceived(createUpdate(message));

        verify(userRegistrationService).registerIfNew(user);
        verifyNoInteractions(commandService);
        assertThat(bot.sentResponse).isNull();
    }

    @Test
    void shouldCreateAndSendResponseForTextMessage() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);
        when(user.getUserName()).thenReturn("daily_user");

        Message message = mock(Message.class);
        when(message.getFrom()).thenReturn(user);
        when(message.hasText()).thenReturn(true);
        when(message.getChatId()).thenReturn(1001L);
        when(message.getText()).thenReturn("/start");

        SendMessage response = new SendMessage();
        response.setChatId("1001");
        response.setText("Xin chào");
        when(commandService.createResponse(message)).thenReturn(Optional.of(response));

        bot.onUpdateReceived(createUpdate(message));

        verify(userRegistrationService).registerIfNew(user);
        verify(commandService).createResponse(message);
        assertThat(bot.sentResponse).isSameAs(response);
    }

    @Test
    void shouldAnswerCallbackAndSendGeneratedResponse() {
        User user = mock(User.class);
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);

        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(callbackQuery.getId()).thenReturn("callback-1");
        when(callbackQuery.getFrom()).thenReturn(user);
        when(callbackQuery.getMessage()).thenReturn(message);

        SendMessage response = new SendMessage();
        response.setChatId("1001");
        response.setText("Vui lòng nhập nội dung báo cáo hôm nay.");
        when(commandService.createResponse(callbackQuery)).thenReturn(Optional.of(response));

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);

        bot.onUpdateReceived(update);

        verify(userRegistrationService).registerIfNew(user);
        verify(commandService).createResponse(callbackQuery);
        assertThat(bot.answeredCallbackQuery.getCallbackQueryId()).isEqualTo("callback-1");
        assertThat(bot.sentResponse).isSameAs(response);
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

        private CapturingDailyReportBot(
                TelegramBotProperties properties,
                TelegramCommandService commandService,
                TelegramUserRegistrationService userRegistrationService
        ) {
            super(properties, commandService, userRegistrationService);
        }

        @Override
        public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method)
                throws TelegramApiException {
            if (method instanceof SendMessage sendMessage) {
                sentResponse = sendMessage;
            }
            if (method instanceof AnswerCallbackQuery answerCallbackQuery) {
                answeredCallbackQuery = answerCallbackQuery;
            }
            return null;
        }
    }
}
