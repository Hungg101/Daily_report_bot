package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.Department;
import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmployeeReportStatusServiceTest {

    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final DailyReportRepository dailyReportRepository = mock(DailyReportRepository.class);
    private final EmployeeReportStatusService service = new EmployeeReportStatusService(
            employeeRepository,
            dailyReportRepository
    );

    @Test
    void shouldFindDailyStatusesForActiveEmployees() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee submittedEmployee = createEmployee(10L, "EMP001");
        Employee notSubmittedEmployee = createEmployee(11L, "EMP002");
        DailyReport latestReport = createReport(
                submittedEmployee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Báo cáo cuối ngày"
        );
        DailyReport previousReport = createReport(
                submittedEmployee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 9, 0),
                "Báo cáo buổi sáng"
        );

        when(employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE))
                .thenReturn(List.of(submittedEmployee, notSubmittedEmployee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L, 11L),
                reportDate
        )).thenReturn(List.of(latestReport, previousReport));

        List<EmployeeDailyReportStatus> statuses = service.findDailyStatuses(reportDate);

        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0).employee()).isSameAs(submittedEmployee);
        assertThat(statuses.get(0).reportDate()).isEqualTo(reportDate);
        assertThat(statuses.get(0).status()).isEqualTo(EmployeeReportSubmissionStatus.SUBMITTED);
        assertThat(statuses.get(0).reportCount()).isEqualTo(2);
        assertThat(statuses.get(0).latestReport()).isSameAs(latestReport);
        assertThat(statuses.get(1).employee()).isSameAs(notSubmittedEmployee);
        assertThat(statuses.get(1).status()).isEqualTo(EmployeeReportSubmissionStatus.NOT_SUBMITTED);
        assertThat(statuses.get(1).reportCount()).isZero();
        assertThat(statuses.get(1).latestReport()).isNull();
        verify(employeeRepository).findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE);
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(List.of(10L, 11L), reportDate);
    }

    @Test
    void shouldFindDailyStatusesForDepartment() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Department department = new Department();
        department.setId(20L);
        Employee employee = createEmployee(10L, "EMP001");
        employee.setDepartment(department);

        when(employeeRepository.findByStatusAndDepartment_IdOrderByCodeAsc(EmployeeStatus.ACTIVE, 20L))
                .thenReturn(List.of(employee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L),
                reportDate
        )).thenReturn(List.of());

        List<EmployeeDailyReportStatus> statuses = service.findDailyStatusesForDepartment(20L, reportDate);

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).employee()).isSameAs(employee);
        assertThat(statuses.get(0).status()).isEqualTo(EmployeeReportSubmissionStatus.NOT_SUBMITTED);
        verify(employeeRepository).findByStatusAndDepartment_IdOrderByCodeAsc(EmployeeStatus.ACTIVE, 20L);
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(List.of(10L), reportDate);
    }

    @Test
    void shouldFindDailyStatusForEmployee() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee employee = createEmployee(10L, "EMP001");
        DailyReport latestReport = createReport(
                employee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Done"
        );

        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L),
                reportDate
        )).thenReturn(List.of(latestReport));

        Optional<EmployeeDailyReportStatus> status = service.findDailyStatusForEmployee(10L, reportDate);

        assertThat(status).isPresent();
        assertThat(status.orElseThrow().employee()).isSameAs(employee);
        assertThat(status.orElseThrow().status()).isEqualTo(EmployeeReportSubmissionStatus.SUBMITTED);
        assertThat(status.orElseThrow().reportCount()).isEqualTo(1);
        assertThat(status.orElseThrow().latestReport()).isSameAs(latestReport);
        verify(employeeRepository).findById(10L);
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(List.of(10L), reportDate);
    }

    @Test
    void shouldFindNotSubmittedDailyStatusForEmployeeCode() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee employee = createEmployee(10L, "EMP001");

        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.of(employee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L),
                reportDate
        )).thenReturn(List.of());

        Optional<EmployeeDailyReportStatus> status = service.findDailyStatusForEmployeeCode("  EMP001  ", reportDate);

        assertThat(status).isPresent();
        assertThat(status.orElseThrow().employee()).isSameAs(employee);
        assertThat(status.orElseThrow().status()).isEqualTo(EmployeeReportSubmissionStatus.NOT_SUBMITTED);
        assertThat(status.orElseThrow().reportCount()).isZero();
        assertThat(status.orElseThrow().latestReport()).isNull();
        verify(employeeRepository).findByCode("EMP001");
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(List.of(10L), reportDate);
    }

    @Test
    void shouldFindDailyReportsForEmployee() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee employee = createEmployee(10L, "EMP001");
        DailyReport latestReport = createReport(
                employee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Done"
        );
        DailyReport previousReport = createReport(
                employee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 9, 0),
                "Morning update"
        );

        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdAndReportDateOrderByCreatedAtDesc(10L, reportDate))
                .thenReturn(List.of(latestReport, previousReport));

        List<DailyReport> reports = service.findDailyReportsForEmployee(10L, reportDate);

        assertThat(reports).containsExactly(latestReport, previousReport);
        verify(employeeRepository).findById(10L);
        verify(dailyReportRepository).findByTelegramUser_Employee_IdAndReportDateOrderByCreatedAtDesc(
                10L,
                reportDate
        );
    }

    @Test
    void shouldFindDailyReportsForEmployeeCode() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee employee = createEmployee(10L, "EMP001");
        DailyReport report = createReport(
                employee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Done"
        );

        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.of(employee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdAndReportDateOrderByCreatedAtDesc(10L, reportDate))
                .thenReturn(List.of(report));

        List<DailyReport> reports = service.findDailyReportsForEmployeeCode("  EMP001  ", reportDate);

        assertThat(reports).containsExactly(report);
        verify(employeeRepository).findByCode("EMP001");
        verify(dailyReportRepository).findByTelegramUser_Employee_IdAndReportDateOrderByCreatedAtDesc(
                10L,
                reportDate
        );
    }

    @Test
    void shouldReturnEmptyDailyReportsWhenEmployeeIsInactive() {
        Employee employee = createEmployee(10L, "EMP001");
        employee.setStatus(EmployeeStatus.INACTIVE);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));

        List<DailyReport> reports = service.findDailyReportsForEmployee(
                10L,
                LocalDate.of(2026, 6, 25)
        );

        assertThat(reports).isEmpty();
        verify(employeeRepository).findById(10L);
        verifyNoInteractions(dailyReportRepository);
    }

    @Test
    void shouldReturnEmptyDailyReportsWhenEmployeeCodeIsBlank() {
        List<DailyReport> reports = service.findDailyReportsForEmployeeCode(
                "   ",
                LocalDate.of(2026, 6, 25)
        );

        assertThat(reports).isEmpty();
        verifyNoInteractions(employeeRepository, dailyReportRepository);
    }

    @Test
    void shouldReturnEmptyDailyStatusWhenEmployeeIsInactive() {
        Employee employee = createEmployee(10L, "EMP001");
        employee.setStatus(EmployeeStatus.INACTIVE);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));

        Optional<EmployeeDailyReportStatus> status = service.findDailyStatusForEmployee(
                10L,
                LocalDate.of(2026, 6, 25)
        );

        assertThat(status).isEmpty();
        verify(employeeRepository).findById(10L);
        verifyNoInteractions(dailyReportRepository);
    }

    @Test
    void shouldReturnEmptyDailyStatusWhenEmployeeCodeIsBlank() {
        Optional<EmployeeDailyReportStatus> status = service.findDailyStatusForEmployeeCode(
                "   ",
                LocalDate.of(2026, 6, 25)
        );

        assertThat(status).isEmpty();
        verifyNoInteractions(employeeRepository, dailyReportRepository);
    }

    @Test
    void shouldFindSubmittedDailyStatuses() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee submittedEmployee = createEmployee(10L, "EMP001");
        Employee notSubmittedEmployee = createEmployee(11L, "EMP002");
        DailyReport report = createReport(
                submittedEmployee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Done"
        );

        when(employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE))
                .thenReturn(List.of(submittedEmployee, notSubmittedEmployee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L, 11L),
                reportDate
        )).thenReturn(List.of(report));

        List<EmployeeDailyReportStatus> statuses = service.findSubmittedDailyStatuses(reportDate);

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).employee()).isSameAs(submittedEmployee);
        assertThat(statuses.get(0).status()).isEqualTo(EmployeeReportSubmissionStatus.SUBMITTED);
        verify(employeeRepository).findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE);
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(List.of(10L, 11L), reportDate);
    }

    @Test
    void shouldFindNotSubmittedDailyStatusesForDepartment() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee submittedEmployee = createEmployee(10L, "EMP001");
        Employee notSubmittedEmployee = createEmployee(11L, "EMP002");
        DailyReport report = createReport(
                submittedEmployee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Done"
        );

        when(employeeRepository.findByStatusAndDepartment_IdOrderByCodeAsc(EmployeeStatus.ACTIVE, 20L))
                .thenReturn(List.of(submittedEmployee, notSubmittedEmployee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L, 11L),
                reportDate
        )).thenReturn(List.of(report));

        List<EmployeeDailyReportStatus> statuses = service.findNotSubmittedDailyStatusesForDepartment(20L, reportDate);

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).employee()).isSameAs(notSubmittedEmployee);
        assertThat(statuses.get(0).status()).isEqualTo(EmployeeReportSubmissionStatus.NOT_SUBMITTED);
        verify(employeeRepository).findByStatusAndDepartment_IdOrderByCodeAsc(EmployeeStatus.ACTIVE, 20L);
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(List.of(10L, 11L), reportDate);
    }

    @Test
    void shouldSummarizeDailyStatuses() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        Employee firstSubmittedEmployee = createEmployee(10L, "EMP001");
        Employee secondSubmittedEmployee = createEmployee(11L, "EMP002");
        Employee notSubmittedEmployee = createEmployee(12L, "EMP003");
        DailyReport firstReport = createReport(
                firstSubmittedEmployee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 0),
                "Done 1"
        );
        DailyReport secondReport = createReport(
                secondSubmittedEmployee,
                reportDate,
                LocalDateTime.of(2026, 6, 25, 18, 5),
                "Done 2"
        );

        when(employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE))
                .thenReturn(List.of(firstSubmittedEmployee, secondSubmittedEmployee, notSubmittedEmployee));
        when(dailyReportRepository.findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                List.of(10L, 11L, 12L),
                reportDate
        )).thenReturn(List.of(secondReport, firstReport));

        EmployeeReportStatusSummary summary = service.summarizeDailyStatuses(reportDate);

        assertThat(summary.reportDate()).isEqualTo(reportDate);
        assertThat(summary.totalEmployees()).isEqualTo(3);
        assertThat(summary.submittedCount()).isEqualTo(2);
        assertThat(summary.notSubmittedCount()).isEqualTo(1);
        verify(employeeRepository).findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE);
        verify(dailyReportRepository)
                .findByTelegramUser_Employee_IdInAndReportDateOrderByCreatedAtDesc(
                        List.of(10L, 11L, 12L),
                        reportDate
                );
    }

    @Test
    void shouldSummarizeDepartmentDailyStatusesWhenDepartmentIsMissing() {
        EmployeeReportStatusSummary summary = service.summarizeDailyStatusesForDepartment(
                null,
                LocalDate.of(2026, 6, 25)
        );

        assertThat(summary.totalEmployees()).isZero();
        assertThat(summary.submittedCount()).isZero();
        assertThat(summary.notSubmittedCount()).isZero();
        verifyNoInteractions(employeeRepository, dailyReportRepository);
    }

    @Test
    void shouldReturnEmptyStatusesWhenReportDateIsMissing() {
        List<EmployeeDailyReportStatus> statuses = service.findDailyStatuses(null);

        assertThat(statuses).isEmpty();
        verifyNoInteractions(employeeRepository, dailyReportRepository);
    }

    @Test
    void shouldReturnEmptyDepartmentStatusesWhenDepartmentIdIsMissing() {
        List<EmployeeDailyReportStatus> statuses = service.findDailyStatusesForDepartment(
                null,
                LocalDate.of(2026, 6, 25)
        );

        assertThat(statuses).isEmpty();
        verifyNoInteractions(employeeRepository, dailyReportRepository);
    }

    @Test
    void shouldSkipReportQueryWhenThereAreNoActiveEmployees() {
        LocalDate reportDate = LocalDate.of(2026, 6, 25);
        when(employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE)).thenReturn(List.of());

        List<EmployeeDailyReportStatus> statuses = service.findDailyStatuses(reportDate);

        assertThat(statuses).isEmpty();
        verify(employeeRepository).findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE);
        verifyNoInteractions(dailyReportRepository);
    }

    private Employee createEmployee(Long id, String code) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setCode(code);
        employee.setFullName("Employee " + code);
        employee.setStatus(EmployeeStatus.ACTIVE);
        return employee;
    }

    private DailyReport createReport(
            Employee employee,
            LocalDate reportDate,
            LocalDateTime createdAt,
            String content
    ) {
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(1000L + employee.getId());
        telegramUser.setEmployee(employee);

        DailyReport report = new DailyReport();
        report.setTelegramUser(telegramUser);
        report.setReportDate(reportDate);
        report.setCreatedAt(createdAt);
        report.setContent(content);
        return report;
    }
}
