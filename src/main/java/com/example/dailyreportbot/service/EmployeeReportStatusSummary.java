package com.example.dailyreportbot.service;

import java.time.LocalDate;

public record EmployeeReportStatusSummary(
        LocalDate reportDate,
        int totalEmployees,
        int submittedCount,
        int notSubmittedCount
) {
}
