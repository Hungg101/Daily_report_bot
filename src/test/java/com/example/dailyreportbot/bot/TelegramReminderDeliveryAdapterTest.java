package com.example.dailyreportbot.bot;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.ReminderDeliveryOutcome;
import com.example.dailyreportbot.service.TelegramCommandService;
import com.example.dailyreportbot.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.io.Serializable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.LoggerFactory.getLogger;
import static org.mockito.Mockito.mock;

class TelegramReminderDeliveryAdapterTest {

    private CapturingDailyReportBot bot;
    private TelegramReminderDeliveryAdapter adapter;

    @BeforeEach
    void setUp() {
        bot = new CapturingDailyReportBot();
        adapter = new TelegramReminderDeliveryAdapter(bot);
    }

    @Test
    void shouldSendOnlyTheFixedReminderTextAndMapSuccessfulApiAcceptance() {
        ReminderDeliveryOutcome outcome = adapter.deliver(88001L);

        assertThat(outcome).isEqualTo(ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);
        assertThat(bot.sentMessage).isNotNull();
        assertThat(bot.sentMessage.getChatId()).isEqualTo("88001");
        assertThat(bot.sentMessage.getText()).isEqualTo("Nhắc bạn: vui lòng gửi báo cáo ngày hôm nay trước 17:00.");
    }

    @Test
    void shouldMapKnownTelegramRejectionWithoutExposingTheApiResponse() {
        String sensitiveResponse = "private Telegram API response";
        bot.exception = new TelegramApiRequestException(sensitiveResponse);

        ReminderDeliveryOutcome outcome = adapter.deliver(88002L);

        assertThat(outcome).isEqualTo(ReminderDeliveryOutcome.TELEGRAM_REJECTED);
        assertThat(bot.sentMessage).isNotNull();
    }

    @Test
    void shouldMapUncertainTransportExceptionToUnknownWithoutRetrySignal() {
        bot.exception = new TelegramApiException("transport timeout with private details");

        ReminderDeliveryOutcome outcome = adapter.deliver(88003L);

        assertThat(outcome).isEqualTo(ReminderDeliveryOutcome.UNKNOWN_TRANSPORT);
    }

    @Test
    void shouldLogOnlyTheBoundedOutcomeCategory() {
        String privateApiResponse = "private Telegram API response";
        String privateChatId = "88004";
        bot.exception = new TelegramApiRequestException(privateApiResponse);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(TelegramReminderDeliveryAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            adapter.deliver(Long.valueOf(privateChatId));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Reminder Telegram delivery did not complete - outcome=TELEGRAM_REJECTED")
                .allSatisfy(message -> {
                    assertThat(message).doesNotContain(privateApiResponse);
                    assertThat(message).doesNotContain(privateChatId);
                    assertThat(message).doesNotContain("test-token");
                });
    }

    private static class CapturingDailyReportBot extends DailyReportBot {

        private SendMessage sentMessage;
        private TelegramApiException exception;

        private CapturingDailyReportBot() {
            super(properties(), mock(TelegramCommandService.class), mock(UserRegistrationService.class));
        }

        @Override
        public <T extends Serializable, Method extends BotApiMethod<T>> T execute(Method method)
                throws TelegramApiException {
            if (method instanceof SendMessage sendMessage) {
                sentMessage = sendMessage;
            }
            if (exception != null) {
                throw exception;
            }
            return null;
        }

        private static TelegramBotProperties properties() {
            TelegramBotProperties properties = new TelegramBotProperties();
            properties.setUsername("reminder_test_bot");
            properties.setToken("test-token");
            return properties;
        }
    }
}
