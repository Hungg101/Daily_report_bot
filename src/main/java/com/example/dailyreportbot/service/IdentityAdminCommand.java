package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.UserStatus;

public record IdentityAdminCommand(
        Long telegramUserId,
        Long chatId,
        String username,
        String firstName,
        String phoneNumber,
        String employeeCode,
        String fullName,
        String departmentName,
        String unitName,
        UserStatus status,
        String actor,
        String reason
) {
}
