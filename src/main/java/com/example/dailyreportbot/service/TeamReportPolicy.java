package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.UserStatus;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class TeamReportPolicy {

    static final LocalTime WORKDAY_START = LocalTime.of(8, 0);
    static final LocalTime DEADLINE = LocalTime.of(17, 0);
    static final LocalTime GRACE_ENDPOINT = LocalTime.of(17, 30);
    private static final Set<DayOfWeek> EXCLUDED_DAYS = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    public TeamReportStatus evaluate(
            UserStatus userStatus,
            LocalDate businessDate,
            LocalDateTime evaluatedAt,
            List<LocalDateTime> submissionTimes
    ) {
        Objects.requireNonNull(userStatus, "userStatus must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(submissionTimes, "submissionTimes must not be null");

        if (evaluatedAt.toLocalDate().isBefore(businessDate)) {
            throw new IllegalArgumentException("Evaluation instant is before business date");
        }
        if (userStatus == UserStatus.INACTIVE || EXCLUDED_DAYS.contains(businessDate.getDayOfWeek())) {
            return TeamReportStatus.EXCLUDED;
        }

        LocalDateTime firstSubmission = submissionTimes.stream()
                .filter(Objects::nonNull)
                .filter(submission -> !submission.isAfter(evaluatedAt))
                .min(LocalDateTime::compareTo)
                .orElse(null);
        if (firstSubmission != null) {
            return firstSubmission.compareTo(businessDate.atTime(DEADLINE)) <= 0
                    ? TeamReportStatus.SUBMITTED_ON_TIME
                    : TeamReportStatus.SUBMITTED_LATE;
        }

        LocalDateTime graceEndpoint = businessDate.atTime(GRACE_ENDPOINT);
        return evaluatedAt.isAfter(graceEndpoint) ? TeamReportStatus.ABSENT : TeamReportStatus.DUE;
    }
}
