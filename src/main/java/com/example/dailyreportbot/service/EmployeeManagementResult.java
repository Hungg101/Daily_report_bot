package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Employee;

public record EmployeeManagementResult(EmployeeManagementStatus status, Employee employee) {
}
