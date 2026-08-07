package com.example.dailyreportbot.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record TeamReportEvidence(
        Long reportId,
        LocalDate reportDate,
        String content,
        LocalDateTime createdAt
) {

    public TeamReportEvidence {
        Objects.requireNonNull(reportId, "reportId must not be null");
        Objects.requireNonNull(reportDate, "reportDate must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
