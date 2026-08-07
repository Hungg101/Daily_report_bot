package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;

import java.util.List;
import java.util.Objects;

public record ManagementReadResult(
        IdentityAdminStatus status,
        List<User> users,
        List<DailyReport> reports,
        List<IdentityAuditEvent> auditEvents
) {
    public ManagementReadResult {
        Objects.requireNonNull(status, "status");
        users = users == null ? List.of() : List.copyOf(users);
        reports = reports == null ? List.of() : List.copyOf(reports);
        auditEvents = auditEvents == null ? List.of() : List.copyOf(auditEvents);
    }
}
