package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.Employee;

import java.time.LocalDate;

public record EmployeeDailyReportStatus(
        Employee employee,
        LocalDate reportDate,
        EmployeeReportSubmissionStatus status,
        int reportCount,
        DailyReport latestReport
) {
}
