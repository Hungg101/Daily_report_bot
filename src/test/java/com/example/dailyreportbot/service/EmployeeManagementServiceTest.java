package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Department;
import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import com.example.dailyreportbot.repository.DepartmentRepository;
import com.example.dailyreportbot.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmployeeManagementServiceTest {

    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final EmployeeManagementService service = new EmployeeManagementService(
            employeeRepository,
            departmentRepository
    );

    @Test
    void shouldCreateEmployeeWithDepartment() {
        Department department = createDepartment(10L, true);
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.empty());
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(department));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeManagementResult result = service.createEmployee(
                "  EMP001  ",
                "  Alice Nguyen  ",
                "  alice@example.com  ",
                10L
        );

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(captor.capture());
        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.CREATED);
        assertThat(result.employee()).isSameAs(captor.getValue());
        assertThat(captor.getValue().getCode()).isEqualTo("EMP001");
        assertThat(captor.getValue().getFullName()).isEqualTo("Alice Nguyen");
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(captor.getValue().getDepartment()).isSameAs(department);
        assertThat(captor.getValue().getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void shouldCreateEmployeeWithoutOptionalEmailOrDepartment() {
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.empty());
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeManagementResult result = service.createEmployee("EMP001", "Alice Nguyen", "   ", null);

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(captor.capture());
        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.CREATED);
        assertThat(captor.getValue().getEmail()).isNull();
        assertThat(captor.getValue().getDepartment()).isNull();
        verify(employeeRepository, never()).findByEmailIgnoreCase(org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(departmentRepository);
    }

    @Test
    void shouldReturnCodeRequiredWhenCreatingEmployeeWithBlankCode() {
        EmployeeManagementResult result = service.createEmployee("   ", "Alice Nguyen", null, null);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.CODE_REQUIRED);
        assertThat(result.employee()).isNull();
        verifyNoInteractions(employeeRepository, departmentRepository);
    }

    @Test
    void shouldReturnFullNameRequiredWhenCreatingEmployeeWithBlankFullName() {
        EmployeeManagementResult result = service.createEmployee("EMP001", "   ", null, null);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.FULL_NAME_REQUIRED);
        assertThat(result.employee()).isNull();
        verifyNoInteractions(employeeRepository, departmentRepository);
    }

    @Test
    void shouldReturnCodeAlreadyExistsWhenCreatingEmployeeWithDuplicateCode() {
        Employee existingEmployee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.of(existingEmployee));

        EmployeeManagementResult result = service.createEmployee("EMP001", "Alice Nguyen", null, null);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.CODE_ALREADY_EXISTS);
        assertThat(result.employee()).isNull();
        verify(employeeRepository, never()).save(any(Employee.class));
        verifyNoInteractions(departmentRepository);
    }

    @Test
    void shouldReturnEmailAlreadyExistsWhenCreatingEmployeeWithDuplicateEmail() {
        Employee existingEmployee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        when(employeeRepository.findByCode("EMP002")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(existingEmployee));

        EmployeeManagementResult result = service.createEmployee(
                "EMP002",
                "Alice Nguyen",
                "alice@example.com",
                null
        );

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.EMAIL_ALREADY_EXISTS);
        assertThat(result.employee()).isNull();
        verify(employeeRepository, never()).save(any(Employee.class));
        verifyNoInteractions(departmentRepository);
    }

    @Test
    void shouldReturnDepartmentNotFoundWhenCreatingEmployeeWithMissingDepartment() {
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.empty());
        when(departmentRepository.findById(10L)).thenReturn(Optional.empty());

        EmployeeManagementResult result = service.createEmployee("EMP001", "Alice Nguyen", null, 10L);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.DEPARTMENT_NOT_FOUND);
        assertThat(result.employee()).isNull();
        verify(employeeRepository, never()).save(any(Employee.class));
    }

    @Test
    void shouldReturnDepartmentInactiveWhenCreatingEmployeeWithInactiveDepartment() {
        Department department = createDepartment(10L, false);
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.empty());
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(department));

        EmployeeManagementResult result = service.createEmployee("EMP001", "Alice Nguyen", null, 10L);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.DEPARTMENT_INACTIVE);
        assertThat(result.employee()).isNull();
        verify(employeeRepository, never()).save(any(Employee.class));
    }

    @Test
    void shouldDeactivateEmployee() {
        Employee employee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeManagementResult result = service.deactivateEmployee(10L);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.UPDATED);
        assertThat(result.employee()).isSameAs(employee);
        assertThat(result.employee().getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
        verify(employeeRepository).save(employee);
    }

    @Test
    void shouldActivateEmployee() {
        Employee employee = createEmployee(10L, "EMP001", EmployeeStatus.INACTIVE);
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeManagementResult result = service.activateEmployee(10L);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.UPDATED);
        assertThat(result.employee()).isSameAs(employee);
        assertThat(result.employee().getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        verify(employeeRepository).save(employee);
    }

    @Test
    void shouldReturnEmployeeNotFoundWhenChangingMissingEmployee() {
        when(employeeRepository.findById(10L)).thenReturn(Optional.empty());

        EmployeeManagementResult result = service.deactivateEmployee(10L);

        assertThat(result.status()).isEqualTo(EmployeeManagementStatus.EMPLOYEE_NOT_FOUND);
        assertThat(result.employee()).isNull();
        verify(employeeRepository, never()).save(any(Employee.class));
    }

    @Test
    void shouldFindActiveEmployees() {
        Employee firstEmployee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        Employee secondEmployee = createEmployee(11L, "EMP002", EmployeeStatus.ACTIVE);
        when(employeeRepository.findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE))
                .thenReturn(List.of(firstEmployee, secondEmployee));

        List<Employee> employees = service.findActiveEmployees();

        assertThat(employees).containsExactly(firstEmployee, secondEmployee);
        verify(employeeRepository).findByStatusOrderByCodeAsc(EmployeeStatus.ACTIVE);
    }

    private Department createDepartment(Long id, boolean active) {
        Department department = new Department();
        department.setId(id);
        department.setName("Backend");
        department.setActive(active);
        return department;
    }

    private Employee createEmployee(Long id, String code, EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setCode(code);
        employee.setFullName("Employee " + code);
        employee.setStatus(status);
        return employee;
    }
}
