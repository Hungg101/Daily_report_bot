package com.example.dailyreportbot.service;

public enum ReminderDeliveryOutcome {
    ACCEPTED_BY_TELEGRAM,
    MISSING_CHAT,
    TELEGRAM_REJECTED,
    UNKNOWN_TRANSPORT
}
