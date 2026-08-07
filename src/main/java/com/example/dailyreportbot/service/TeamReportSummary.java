package com.example.dailyreportbot.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TeamReportSummary(
        TeamScope scope,
        LocalDate businessDate,
        Instant evaluatedAt,
        ZoneId zoneId,
        int totalEmployees,
        Map<TeamReportStatus, Long> counts,
        List<TeamMemberReportStatus> members
) {

    public TeamReportSummary {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
        Objects.requireNonNull(counts, "counts must not be null");
        members = List.copyOf(Objects.requireNonNull(members, "members must not be null"));

        EnumMap<TeamReportStatus, Long> normalizedCounts = new EnumMap<>(TeamReportStatus.class);
        for (TeamReportStatus status : TeamReportStatus.values()) {
            long count = counts.getOrDefault(status, 0L);
            if (count < 0) {
                throw new IllegalArgumentException("Status count must not be negative");
            }
            normalizedCounts.put(status, count);
        }
        counts = Collections.unmodifiableMap(normalizedCounts);

        long countedEmployees = normalizedCounts.values().stream().mapToLong(Long::longValue).sum();
        if (totalEmployees < 0 || totalEmployees != members.size() || countedEmployees != totalEmployees) {
            throw new IllegalArgumentException("Summary counts must match employee details");
        }
    }

    public long count(TeamReportStatus status) {
        return counts.getOrDefault(Objects.requireNonNull(status), 0L);
    }
}
