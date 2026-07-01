package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Department;
import com.example.dailyreportbot.repository.DepartmentRepository;
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

class DepartmentManagementServiceTest {

    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final DepartmentManagementService service = new DepartmentManagementService(departmentRepository);

    @Test
    void shouldCreateDepartment() {
        when(departmentRepository.findByNameIgnoreCase("Backend")).thenReturn(Optional.empty());
        when(departmentRepository.save(any(Department.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DepartmentManagementResult result = service.createDepartment("  Backend  ", "  API team  ");

        ArgumentCaptor<Department> captor = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(captor.capture());
        assertThat(result.status()).isEqualTo(DepartmentManagementStatus.CREATED);
        assertThat(result.department()).isSameAs(captor.getValue());
        assertThat(captor.getValue().getName()).isEqualTo("Backend");
        assertThat(captor.getValue().getDescription()).isEqualTo("API team");
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void shouldReturnNameRequiredWhenCreatingDepartmentWithBlankName() {
        DepartmentManagementResult result = service.createDepartment("   ", "API team");

        assertThat(result.status()).isEqualTo(DepartmentManagementStatus.NAME_REQUIRED);
        assertThat(result.department()).isNull();
        verifyNoInteractions(departmentRepository);
    }

    @Test
    void shouldReturnNameAlreadyExistsWhenCreatingDuplicateDepartment() {
        Department existingDepartment = createDepartment(10L, "Backend", true);
        when(departmentRepository.findByNameIgnoreCase("Backend")).thenReturn(Optional.of(existingDepartment));

        DepartmentManagementResult result = service.createDepartment("Backend", "API team");

        assertThat(result.status()).isEqualTo(DepartmentManagementStatus.NAME_ALREADY_EXISTS);
        assertThat(result.department()).isNull();
        verify(departmentRepository, never()).save(any(Department.class));
    }

    @Test
    void shouldDeactivateDepartment() {
        Department department = createDepartment(10L, "Backend", true);
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(department));
        when(departmentRepository.save(any(Department.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DepartmentManagementResult result = service.deactivateDepartment(10L);

        assertThat(result.status()).isEqualTo(DepartmentManagementStatus.UPDATED);
        assertThat(result.department()).isSameAs(department);
        assertThat(result.department().isActive()).isFalse();
        verify(departmentRepository).save(department);
    }

    @Test
    void shouldActivateDepartment() {
        Department department = createDepartment(10L, "Backend", false);
        when(departmentRepository.findById(10L)).thenReturn(Optional.of(department));
        when(departmentRepository.save(any(Department.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DepartmentManagementResult result = service.activateDepartment(10L);

        assertThat(result.status()).isEqualTo(DepartmentManagementStatus.UPDATED);
        assertThat(result.department()).isSameAs(department);
        assertThat(result.department().isActive()).isTrue();
        verify(departmentRepository).save(department);
    }

    @Test
    void shouldReturnDepartmentNotFoundWhenChangingMissingDepartment() {
        when(departmentRepository.findById(10L)).thenReturn(Optional.empty());

        DepartmentManagementResult result = service.deactivateDepartment(10L);

        assertThat(result.status()).isEqualTo(DepartmentManagementStatus.DEPARTMENT_NOT_FOUND);
        assertThat(result.department()).isNull();
        verify(departmentRepository, never()).save(any(Department.class));
    }

    @Test
    void shouldFindActiveDepartments() {
        Department backend = createDepartment(10L, "Backend", true);
        Department qa = createDepartment(11L, "QA", true);
        when(departmentRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(backend, qa));

        List<Department> departments = service.findActiveDepartments();

        assertThat(departments).containsExactly(backend, qa);
        verify(departmentRepository).findByActiveTrueOrderByNameAsc();
    }

    private Department createDepartment(Long id, String name, boolean active) {
        Department department = new Department();
        department.setId(id);
        department.setName(name);
        department.setActive(active);
        return department;
    }
}
