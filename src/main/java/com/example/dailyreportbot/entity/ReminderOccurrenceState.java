package com.example.dailyreportbot.entity;

public enum ReminderOccurrenceState {
    PENDING,
    RETRY_ELIGIBLE,
    DELIVERED,
    FAILED,
    UNKNOWN,
    SUPPRESSED,
    EXPIRED
}
