package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByCode(String code);

    Optional<Employee> findByEmailIgnoreCase(String email);

    List<Employee> findByStatusOrderByCodeAsc(EmployeeStatus status);

    List<Employee> findByStatusAndDepartment_IdOrderByCodeAsc(EmployeeStatus status, Long departmentId);
}
