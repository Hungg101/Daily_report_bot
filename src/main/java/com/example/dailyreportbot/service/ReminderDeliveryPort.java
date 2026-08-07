package com.example.dailyreportbot.service;

public interface ReminderDeliveryPort {

    ReminderDeliveryOutcome deliver(Long chatId);
}
