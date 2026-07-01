package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByName(String name);

    Optional<Department> findByNameIgnoreCase(String name);

    List<Department> findByActiveTrueOrderByNameAsc();
}
