package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class EmployeeReportStatusService {

    private final EmployeeRepository employeeRepository;
    private final DailyReportRepository dailyReportRepository;

    public EmployeeReportStatusService(
            EmployeeRepository employeeRepository,
            DailyReportRepository dailyReportRepository
    ) {
        this.employeeRepository = employeeRepository;
        this.dailyReportRepository = dailyReportRepository;
    }

    @Transactional(readOnly = true)
    public List<EmployeeDailyReportStatus> findDailyStatuses(LocalDate reportDate) {
        if (reportDate == null) {
            return List.of();
        }

        return buildDailyStatuses(
                employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE),
                reportDate
        );
    }

    @Transactional(readOnly = true)
    public List<EmployeeDailyReportStatus> findDailyStatusesForDepartment(Long departmentId, LocalDate reportDate) {
        if (departmentId == null || reportDate == null) {
            return List.of();
        }

        return buildDailyStatuses(
                employeeRepository.findByStatusAndDepartment_IdOrderByCodeAsc(EmployeeStatus.ACTIVE, departmentId),
                reportDate
        );
    }

    @Transactional(readOnly = true)
    public Optional<EmployeeDailyReportStatus> findDailyStatusForEmployee(Long employeeId, LocalDate reportDate) {
        if (employeeId == null || reportDate == null) {
            return Optional.empty();
        }

        return employeeRepository.findById(employeeId)
                .filter(this::isActiveEmployee)
                .flatMap(employee -> buildDailyStatuses(List.of(employee), reportDate).stream().findFirst());
    }

    @Transactional(readOnly = true)
    public Optional<EmployeeDailyReportStatus> findDailyStatusForEmployeeCode(
            String employeeCode,
            LocalDate reportDate
    ) {
        String normalizedEmployeeCode = normalizeEmployeeCode(employeeCode);
        if (normalizedEmployeeCode == null || reportDate == null) {
            return Optional.empty();
        }

        return employeeRepository.findByCode(normalizedEmployeeCode)
                .filter(this::isActiveEmployee)
                .flatMap(employee -> buildDailyStatuses(List.of(employee), reportDate).stream().findFirst());
    }

    @Transactional(readOnly = true)
    public List<DailyReport> findDailyReportsForEmployee(Long employeeId, LocalDate reportDate) {
        if (employeeId == null || reportDate == null) {
            return List.of();
        }

        return employeeRepository.findById(employeeId)
                .filter(this::isActiveEmployee)
                .map(employee -> findDailyReportsForActiveEmployee(employee, reportDate))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<DailyReport> findDailyReportsForEmployeeCode(String employeeCode, LocalDate reportDate) {
        String normalizedEmployeeCode = normalizeEmployeeCode(employeeCode);
        if (normalizedEmployeeCode == null || reportDate == null) {
            return List.of();
        }

        return employeeRepository.findByCode(normalizedEmployeeCode)
                .filter(this::isActiveEmployee)
                .map(employee -> findDailyReportsForActiveEmployee(employee, reportDate))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDailyReportStatus> findSubmittedDailyStatuses(LocalDate reportDate) {
        return filterBySubmissionStatus(
                findDailyStatuses(reportDate),
                EmployeeReportSubmissionStatus.SUBMITTED
        );
    }

    @Transactional(readOnly = true)
    public List<EmployeeDailyReportStatus> findNotSubmittedDailyStatuses(LocalDate reportDate) {
        return filterBySubmissionStatus(
                findDailyStatuses(reportDate),
                EmployeeReportSubmissionStatus.NOT_SUBMITTED
        );
    }

    @Transactional(readOnly = true)
    public List<EmployeeDailyReportStatus> findSubmittedDailyStatusesForDepartment(
            Long departmentId,
            LocalDate reportDate
    ) {
        return filterBySubmissionStatus(
                findDailyStatusesForDepartment(departmentId, reportDate),
                EmployeeReportSubmissionStatus.SUBMITTED
        );
    }

    @Transactional(readOnly = true)
    public List<EmployeeDailyReportStatus> findNotSubmittedDailyStatusesForDepartment(
            Long departmentId,
            LocalDate reportDate
    ) {
        return filterBySubmissionStatus(
                findDailyStatusesForDepartment(departmentId, reportDate),
                EmployeeReportSubmissionStatus.NOT_SUBMITTED
        );
    }

    @Transactional(readOnly = true)
    public EmployeeReportStatusSummary summarizeDailyStatuses(LocalDate reportDate) {
        return summarize(reportDate, findDailyStatuses(reportDate));
    }

    @Transactional(readOnly = true)
    public EmployeeReportStatusSummary summarizeDailyStatusesForDepartment(Long departmentId, LocalDate reportDate) {
        return summarize(reportDate, findDailyStatusesForDepartment(departmentId, reportDate));
    }

    private List<EmployeeDailyReportStatus> buildDailyStatuses(List<Employee> employees, LocalDate reportDate) {
        if (employees == null || employees.isEmpty()) {
            return List.of();
        }

        List<Long> employeeIds = employees.stream()
                .map(Employee::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, List<DailyReport>> reportsByEmployeeId = employeeIds.isEmpty()
                ? Map.of()
                : findReportsByEmployeeId(employeeIds, reportDate);

        return employees.stream()
                .map(employee -> buildDailyStatus(
                        employee,
                        reportDate,
                        reportsByEmployeeId.getOrDefault(employee.getId(), List.of())
                ))
                .toList();
    }

    private Map<Long, List<DailyReport>> findReportsByEmployeeId(List<Long> employeeIds, LocalDate reportDate) {
        List<DailyReport> reports = dailyReportRepository
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(employeeIds, reportDate);

        Map<Long, List<DailyReport>> reportsByEmployeeId = new HashMap<>();
        for (DailyReport report : reports) {
            Long employeeId = resolveMappedEmployeeId(report);
            if (employeeId != null) {
                reportsByEmployeeId.computeIfAbsent(employeeId, ignored -> new ArrayList<>()).add(report);
            }
        }
        return reportsByEmployeeId;
    }

    private List<DailyReport> findDailyReportsForActiveEmployee(Employee employee, LocalDate reportDate) {
        if (employee == null || employee.getId() == null || reportDate == null) {
            return List.of();
        }

        return dailyReportRepository.findByTelegramUser_Employee_IdAndReportDateOrderByCreatedAtDesc(
                employee.getId(),
                reportDate
        );
    }

    private EmployeeDailyReportStatus buildDailyStatus(
            Employee employee,
            LocalDate reportDate,
            List<DailyReport> reports
    ) {
        if (reports.isEmpty()) {
            return new EmployeeDailyReportStatus(
                    employee,
                    reportDate,
                    EmployeeReportSubmissionStatus.NOT_SUBMITTED,
                    0,
                    null
            );
        }

        return new EmployeeDailyReportStatus(
                employee,
                reportDate,
                EmployeeReportSubmissionStatus.SUBMITTED,
                reports.size(),
                reports.get(0)
        );
    }

    private Long resolveMappedEmployeeId(DailyReport report) {
        if (report == null) {
            return null;
        }

        TelegramUser telegramUser = report.getTelegramUser();
        if (telegramUser == null || telegramUser.getEmployee() == null) {
            return null;
        }

        return telegramUser.getEmployee().getId();
    }

    private boolean isActiveEmployee(Employee employee) {
        return employee != null && employee.getStatus() == EmployeeStatus.ACTIVE;
    }

    private String normalizeEmployeeCode(String employeeCode) {
        if (!StringUtils.hasText(employeeCode)) {
            return null;
        }

        return employeeCode.trim();
    }

    private List<EmployeeDailyReportStatus> filterBySubmissionStatus(
            List<EmployeeDailyReportStatus> statuses,
            EmployeeReportSubmissionStatus submissionStatus
    ) {
        if (statuses == null || submissionStatus == null) {
            return List.of();
        }

        return statuses.stream()
                .filter(status -> status.status() == submissionStatus)
                .toList();
    }

    private EmployeeReportStatusSummary summarize(
            LocalDate reportDate,
            List<EmployeeDailyReportStatus> statuses
    ) {
        int submittedCount = countBySubmissionStatus(statuses, EmployeeReportSubmissionStatus.SUBMITTED);
        int notSubmittedCount = countBySubmissionStatus(statuses, EmployeeReportSubmissionStatus.NOT_SUBMITTED);

        return new EmployeeReportStatusSummary(
                reportDate,
                statuses != null ? statuses.size() : 0,
                submittedCount,
                notSubmittedCount
        );
    }

    private int countBySubmissionStatus(
            List<EmployeeDailyReportStatus> statuses,
            EmployeeReportSubmissionStatus submissionStatus
    ) {
        if (statuses == null || submissionStatus == null) {
            return 0;
        }

        return (int) statuses.stream()
                .filter(status -> status.status() == submissionStatus)
                .count();
    }
}
