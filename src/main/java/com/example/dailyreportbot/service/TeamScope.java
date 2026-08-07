package com.example.dailyreportbot.service;

public record TeamScope(String departmentName, String unitName) {

    public TeamScope {
        departmentName = normalizeOptional(departmentName);
        if (departmentName == null) {
            throw new IllegalArgumentException("Department is required");
        }
        unitName = normalizeOptional(unitName);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
