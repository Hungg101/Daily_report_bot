package com.example.dailyreportbot.bot;

import com.example.dailyreportbot.service.ReminderDeliveryOutcome;
import com.example.dailyreportbot.service.ReminderDeliveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

@Component
/**
 * Adapter tích hợp chức năng gửi tin nhắn qua Telegram.
 * Chịu trách nhiệm gửi các tin nhắn nhắc nhở chủ động đến người dùng
 * một cách độc lập thông qua API của Telegram.
 */
public class TelegramReminderDeliveryAdapter implements ReminderDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(TelegramReminderDeliveryAdapter.class);
    private static final String REMINDER_TEXT = "Nhắc bạn: vui lòng gửi báo cáo ngày hôm nay trước 17:00.";

    private final DailyReportBot bot;

    public TelegramReminderDeliveryAdapter(DailyReportBot bot) {
        this.bot = bot;
    }

    @Override
    public ReminderDeliveryOutcome deliver(Long chatId) {
        if (chatId == null || chatId <= 0) {
            return ReminderDeliveryOutcome.MISSING_CHAT;
        }

        SendMessage reminder = new SendMessage(String.valueOf(chatId), REMINDER_TEXT);
        try {
            bot.execute(reminder);
            return ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM;
        } catch (TelegramApiRequestException exception) {
            return failed(ReminderDeliveryOutcome.TELEGRAM_REJECTED);
        } catch (TelegramApiException | RuntimeException exception) {
            return failed(ReminderDeliveryOutcome.UNKNOWN_TRANSPORT);
        }
    }

    private ReminderDeliveryOutcome failed(ReminderDeliveryOutcome outcome) {
        log.warn("Reminder Telegram delivery did not complete - outcome={}", outcome);
        return outcome;
    }
}
