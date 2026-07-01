package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Department;
import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import com.example.dailyreportbot.repository.DepartmentRepository;
import com.example.dailyreportbot.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class EmployeeManagementService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeManagementService.class);

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;

    public EmployeeManagementService(
            EmployeeRepository employeeRepository,
            DepartmentRepository departmentRepository
    ) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
    }

    @Transactional
    public EmployeeManagementResult createEmployee(
            String code,
            String fullName,
            String email,
            Long departmentId
    ) {
        String normalizedCode = normalizeRequiredText(code);
        if (normalizedCode == null) {
            return new EmployeeManagementResult(EmployeeManagementStatus.CODE_REQUIRED, null);
        }

        String normalizedFullName = normalizeRequiredText(fullName);
        if (normalizedFullName == null) {
            return new EmployeeManagementResult(EmployeeManagementStatus.FULL_NAME_REQUIRED, null);
        }

        if (employeeRepository.findByCode(normalizedCode).isPresent()) {
            return new EmployeeManagementResult(EmployeeManagementStatus.CODE_ALREADY_EXISTS, null);
        }

        String normalizedEmail = normalizeOptionalText(email);
        if (normalizedEmail != null && employeeRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            return new EmployeeManagementResult(EmployeeManagementStatus.EMAIL_ALREADY_EXISTS, null);
        }

        Department department = null;
        if (departmentId != null) {
            Optional<Department> departmentLookup = departmentRepository.findById(departmentId);
            if (departmentLookup.isEmpty()) {
                return new EmployeeManagementResult(EmployeeManagementStatus.DEPARTMENT_NOT_FOUND, null);
            }
            department = departmentLookup.get();
            if (!department.isActive()) {
                return new EmployeeManagementResult(EmployeeManagementStatus.DEPARTMENT_INACTIVE, null);
            }
        }

        Employee employee = new Employee();
        employee.setCode(normalizedCode);
        employee.setFullName(normalizedFullName);
        employee.setEmail(normalizedEmail);
        employee.setDepartment(department);
        employee.setStatus(EmployeeStatus.ACTIVE);

        Employee savedEmployee = employeeRepository.save(employee);
        log.info(
                "Employee created - employeeId={}, employeeCode={}, departmentId={}",
                savedEmployee.getId(),
                savedEmployee.getCode(),
                department != null ? department.getId() : null
        );
        return new EmployeeManagementResult(EmployeeManagementStatus.CREATED, savedEmployee);
    }

    @Transactional
    public EmployeeManagementResult deactivateEmployee(Long employeeId) {
        return setEmployeeStatus(employeeId, EmployeeStatus.INACTIVE);
    }

    @Transactional
    public EmployeeManagementResult activateEmployee(Long employeeId) {
        return setEmployeeStatus(employeeId, EmployeeStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Employee> findActiveEmployees() {
        return employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE);
    }

    private EmployeeManagementResult setEmployeeStatus(Long employeeId, EmployeeStatus status) {
        if (employeeId == null) {
            return new EmployeeManagementResult(EmployeeManagementStatus.EMPLOYEE_NOT_FOUND, null);
        }

        return employeeRepository.findById(employeeId)
                .map(employee -> {
                    employee.setStatus(status);
                    Employee savedEmployee = employeeRepository.save(employee);
                    log.info(
                            "Employee status changed - employeeId={}, employeeCode={}, status={}",
                            savedEmployee.getId(),
                            savedEmployee.getCode(),
                            savedEmployee.getStatus()
                    );
                    return new EmployeeManagementResult(EmployeeManagementStatus.UPDATED, savedEmployee);
                })
                .orElseGet(() -> new EmployeeManagementResult(EmployeeManagementStatus.EMPLOYEE_NOT_FOUND, null));
    }

    private String normalizeRequiredText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
