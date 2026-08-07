package com.example.dailyreportbot.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public record TeamMemberReportStatus(
        Long userId,
        Long telegramUserId,
        String employeeCode,
        String fullName,
        String firstName,
        String username,
        String departmentName,
        String unitName,
        String displayName,
        TeamReportStatus status,
        LocalDateTime firstSubmissionAt,
        List<TeamReportEvidence> reports
) {

    public TeamMemberReportStatus {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        reports = List.copyOf(Objects.requireNonNull(reports, "reports must not be null"));
    }
}
