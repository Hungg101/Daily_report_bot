package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

final class TeamReportTestFixtures {

    private TeamReportTestFixtures() {
    }

    static User user(long id, String fullName, String department, String unit, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setFullName(fullName);
        user.setDepartmentName(department);
        user.setUnitName(unit);
        user.setStatus(status);
        return user;
    }

    static DailyReport report(long id, User user, LocalDate date, LocalDateTime createdAt) {
        DailyReport report = new DailyReport();
        report.setId(id);
        report.setUser(user);
        report.setReportDate(date);
        report.setCreatedAt(createdAt);
        report.setContent("Report " + id);
        return report;
    }
}
