package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Department;
import com.example.dailyreportbot.repository.DepartmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class DepartmentManagementService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentManagementService.class);

    private final DepartmentRepository departmentRepository;

    public DepartmentManagementService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Transactional
    public DepartmentManagementResult createDepartment(String name, String description) {
        String normalizedName = normalizeRequiredText(name);
        if (normalizedName == null) {
            return new DepartmentManagementResult(DepartmentManagementStatus.NAME_REQUIRED, null);
        }

        if (departmentRepository.findByNameIgnoreCase(normalizedName).isPresent()) {
            return new DepartmentManagementResult(DepartmentManagementStatus.NAME_ALREADY_EXISTS, null);
        }

        Department department = new Department();
        department.setName(normalizedName);
        department.setDescription(normalizeOptionalText(description));
        department.setActive(true);

        Department savedDepartment = departmentRepository.save(department);
        log.info("Department created - departmentId={}, name={}", savedDepartment.getId(), savedDepartment.getName());
        return new DepartmentManagementResult(DepartmentManagementStatus.CREATED, savedDepartment);
    }

    @Transactional
    public DepartmentManagementResult deactivateDepartment(Long departmentId) {
        return setDepartmentActive(departmentId, false);
    }

    @Transactional
    public DepartmentManagementResult activateDepartment(Long departmentId) {
        return setDepartmentActive(departmentId, true);
    }

    @Transactional(readOnly = true)
    public List<Department> findActiveDepartments() {
        return departmentRepository.findByActiveTrueOrderByNameAsc();
    }

    private DepartmentManagementResult setDepartmentActive(Long departmentId, boolean active) {
        if (departmentId == null) {
            return new DepartmentManagementResult(DepartmentManagementStatus.DEPARTMENT_NOT_FOUND, null);
        }

        return departmentRepository.findById(departmentId)
                .map(department -> {
                    department.setActive(active);
                    Department savedDepartment = departmentRepository.save(department);
                    log.info(
                            "Department active flag changed - departmentId={}, active={}",
                            savedDepartment.getId(),
                            savedDepartment.isActive()
                    );
                    return new DepartmentManagementResult(DepartmentManagementStatus.UPDATED, savedDepartment);
                })
                .orElseGet(() -> new DepartmentManagementResult(
                        DepartmentManagementStatus.DEPARTMENT_NOT_FOUND,
                        null
                ));
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
