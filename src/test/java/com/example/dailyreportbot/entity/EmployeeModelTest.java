package com.example.dailyreportbot.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeModelTest {

    @Test
    void shouldCreateActiveDepartmentByDefault() {
        Department department = new Department();

        assertThat(department.isActive()).isTrue();
    }

    @Test
    void shouldCreateActiveEmployeeByDefault() {
        Employee employee = new Employee();

        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void shouldLinkTelegramUserToEmployee() {
        Employee employee = new Employee();
        employee.setCode("EMP001");
        employee.setFullName("Nguyen Van A");

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramUserId(12345L);
        telegramUser.setEmployee(employee);

        assertThat(telegramUser.getEmployee()).isSameAs(employee);
        assertThat(telegramUser.getEmployee().getCode()).isEqualTo("EMP001");
    }

    @Test
    void shouldLinkEmployeeToDepartment() {
        Department department = new Department();
        department.setName("Backend");

        Employee employee = new Employee();
        employee.setDepartment(department);

        assertThat(employee.getDepartment()).isSameAs(department);
        assertThat(employee.getDepartment().getName()).isEqualTo("Backend");
    }
}
