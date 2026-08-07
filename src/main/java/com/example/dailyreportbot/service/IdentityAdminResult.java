package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;

public record IdentityAdminResult(IdentityAdminStatus status, User user) {
}
