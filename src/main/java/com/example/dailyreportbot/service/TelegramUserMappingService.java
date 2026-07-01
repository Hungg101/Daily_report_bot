package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.EmployeeRepository;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

@Service
public class TelegramUserMappingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramUserMappingService.class);

    private final TelegramUserRepository telegramUserRepository;
    private final EmployeeRepository employeeRepository;

    public TelegramUserMappingService(
            TelegramUserRepository telegramUserRepository,
            EmployeeRepository employeeRepository
    ) {
        this.telegramUserRepository = telegramUserRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public TelegramUserMappingStatus linkTelegramUserToEmployee(Long telegramUserId, String employeeCode) {
        if (telegramUserId == null) {
            return TelegramUserMappingStatus.TELEGRAM_USER_NOT_FOUND;
        }

        if (!StringUtils.hasText(employeeCode)) {
            return TelegramUserMappingStatus.EMPLOYEE_NOT_FOUND;
        }

        Optional<TelegramUser> telegramUser = telegramUserRepository.findByTelegramUserId(telegramUserId);
        if (telegramUser.isEmpty()) {
            return TelegramUserMappingStatus.TELEGRAM_USER_NOT_FOUND;
        }

        String normalizedEmployeeCode = employeeCode.trim();
        Optional<Employee> employee = employeeRepository.findByCode(normalizedEmployeeCode);
        if (employee.isEmpty()) {
            return TelegramUserMappingStatus.EMPLOYEE_NOT_FOUND;
        }

        return link(telegramUser.get(), employee.get());
    }

    @Transactional(readOnly = true)
    public Optional<Employee> findMappedEmployee(Long telegramUserId) {
        if (telegramUserId == null) {
            return Optional.empty();
        }

        return telegramUserRepository.findByTelegramUserId(telegramUserId)
                .map(TelegramUser::getEmployee);
    }

    private TelegramUserMappingStatus link(TelegramUser telegramUser, Employee employee) {
        if (employee.getStatus() == EmployeeStatus.INACTIVE) {
            return TelegramUserMappingStatus.EMPLOYEE_INACTIVE;
        }

        Employee currentlyLinkedEmployee = telegramUser.getEmployee();
        if (currentlyLinkedEmployee != null) {
            return TelegramUserMappingStatus.TELEGRAM_USER_ALREADY_LINKED;
        }

        Optional<TelegramUser> existingMappedUser = telegramUserRepository.findByEmployee_Id(employee.getId());
        if (existingMappedUser.isPresent()
                && !Objects.equals(existingMappedUser.get().getTelegramUserId(), telegramUser.getTelegramUserId())) {
            return TelegramUserMappingStatus.EMPLOYEE_ALREADY_LINKED;
        }

        telegramUser.setEmployee(employee);
        telegramUserRepository.save(telegramUser);
        log.info(
                "Telegram user linked to employee - telegramUserId={}, employeeCode={}",
                telegramUser.getTelegramUserId(),
                employee.getCode()
        );
        return TelegramUserMappingStatus.LINKED;
    }
}
