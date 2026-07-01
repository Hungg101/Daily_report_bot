package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.EmployeeRepository;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramUserMappingServiceTest {

    private final TelegramUserRepository telegramUserRepository = mock(TelegramUserRepository.class);
    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final TelegramUserMappingService service = new TelegramUserMappingService(
            telegramUserRepository,
            employeeRepository
    );

    @Test
    void shouldLinkTelegramUserToEmployee() {
        TelegramUser telegramUser = createTelegramUser(12345L);
        Employee employee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.of(employee));
        when(telegramUserRepository.findByEmployee_Id(10L)).thenReturn(Optional.empty());

        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "  EMP001  ");

        ArgumentCaptor<TelegramUser> captor = ArgumentCaptor.forClass(TelegramUser.class);
        verify(telegramUserRepository).save(captor.capture());
        assertThat(status).isEqualTo(TelegramUserMappingStatus.LINKED);
        assertThat(captor.getValue().getEmployee()).isSameAs(employee);
    }

    @Test
    void shouldReturnTelegramUserNotFoundWhenTelegramUserIsMissing() {
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.empty());

        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "EMP001");

        assertThat(status).isEqualTo(TelegramUserMappingStatus.TELEGRAM_USER_NOT_FOUND);
        verifyNoInteractions(employeeRepository);
        verify(telegramUserRepository, never()).save(org.mockito.ArgumentMatchers.any(TelegramUser.class));
    }

    @Test
    void shouldReturnEmployeeNotFoundWhenCodeIsMissing() {
        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "   ");

        assertThat(status).isEqualTo(TelegramUserMappingStatus.EMPLOYEE_NOT_FOUND);
        verifyNoInteractions(employeeRepository);
        verify(telegramUserRepository, never()).save(org.mockito.ArgumentMatchers.any(TelegramUser.class));
    }

    @Test
    void shouldReturnEmployeeNotFoundWhenEmployeeDoesNotExist() {
        TelegramUser telegramUser = createTelegramUser(12345L);
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.empty());

        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "EMP001");

        assertThat(status).isEqualTo(TelegramUserMappingStatus.EMPLOYEE_NOT_FOUND);
        verify(telegramUserRepository, never()).save(org.mockito.ArgumentMatchers.any(TelegramUser.class));
    }

    @Test
    void shouldBlockInactiveEmployeeMapping() {
        TelegramUser telegramUser = createTelegramUser(12345L);
        Employee employee = createEmployee(10L, "EMP001", EmployeeStatus.INACTIVE);
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.of(employee));

        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "EMP001");

        assertThat(status).isEqualTo(TelegramUserMappingStatus.EMPLOYEE_INACTIVE);
        verify(telegramUserRepository, never()).findByEmployee_Id(10L);
        verify(telegramUserRepository, never()).save(org.mockito.ArgumentMatchers.any(TelegramUser.class));
    }

    @Test
    void shouldBlockTelegramUserAlreadyLinkedToEmployee() {
        Employee existingEmployee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        Employee newEmployee = createEmployee(11L, "EMP002", EmployeeStatus.ACTIVE);
        TelegramUser telegramUser = createTelegramUser(12345L);
        telegramUser.setEmployee(existingEmployee);
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(employeeRepository.findByCode("EMP002")).thenReturn(Optional.of(newEmployee));

        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "EMP002");

        assertThat(status).isEqualTo(TelegramUserMappingStatus.TELEGRAM_USER_ALREADY_LINKED);
        verify(telegramUserRepository, never()).findByEmployee_Id(11L);
        verify(telegramUserRepository, never()).save(org.mockito.ArgumentMatchers.any(TelegramUser.class));
    }

    @Test
    void shouldBlockEmployeeAlreadyLinkedToAnotherTelegramUser() {
        TelegramUser telegramUser = createTelegramUser(12345L);
        TelegramUser existingMappedUser = createTelegramUser(67890L);
        Employee employee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        existingMappedUser.setEmployee(employee);
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));
        when(employeeRepository.findByCode("EMP001")).thenReturn(Optional.of(employee));
        when(telegramUserRepository.findByEmployee_Id(10L)).thenReturn(Optional.of(existingMappedUser));

        TelegramUserMappingStatus status = service.linkTelegramUserToEmployee(12345L, "EMP001");

        assertThat(status).isEqualTo(TelegramUserMappingStatus.EMPLOYEE_ALREADY_LINKED);
        verify(telegramUserRepository, never()).save(org.mockito.ArgumentMatchers.any(TelegramUser.class));
    }

    @Test
    void shouldFindMappedEmployee() {
        Employee employee = createEmployee(10L, "EMP001", EmployeeStatus.ACTIVE);
        TelegramUser telegramUser = createTelegramUser(12345L);
        telegramUser.setEmployee(employee);
        when(telegramUserRepository.findByTelegramUserId(12345L)).thenReturn(Optional.of(telegramUser));

        Optional<Employee> mappedEmployee = service.findMappedEmployee(12345L);

        assertThat(mappedEmployee).containsSame(employee);
    }

    @Test
    void shouldReturnEmptyMappedEmployeeWhenTelegramUserIdIsMissing() {
        Optional<Employee> mappedEmployee = service.findMappedEmployee(null);

        assertThat(mappedEmployee).isEmpty();
        verifyNoInteractions(telegramUserRepository, employeeRepository);
    }

    private TelegramUser createTelegramUser(Long telegramUserId) {
        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(telegramUserId);
        return telegramUser;
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
