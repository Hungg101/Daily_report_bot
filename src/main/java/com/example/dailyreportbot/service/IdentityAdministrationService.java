package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.IdentityAdminAction;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.IdentityAuditEventRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
/**
 * Dịch vụ quản lý danh tính và phân quyền nhân sự.
 * Hỗ trợ các chức năng quản trị như đổi phòng ban, gán quyền quản lý (Manager),
 * và theo dõi các thay đổi về hồ sơ nhân viên trong tổ chức.
 */
public class IdentityAdministrationService {

    private static final int MAX_ACTOR_LENGTH = 255;
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_PROFILE_LENGTH = 255;

    private final UserRepository userRepository;
    private final DailyReportRepository dailyReportRepository;
    private final IdentityAuditEventRepository auditRepository;
    private final Clock clock;

    public IdentityAdministrationService(
            UserRepository userRepository,
            DailyReportRepository dailyReportRepository,
            IdentityAuditEventRepository auditRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.dailyReportRepository = dailyReportRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    public enum Role {
        MANAGER,
        ADMIN
    }

    public enum RoleChange {
        GRANT,
        REVOKE
    }

    @Transactional
    public IdentityAdminResult createUser(IdentityAdminCommand command) {
        IdentityAdminCommand normalized = normalize(command);
        if (!isValid(normalized) || !StringUtils.hasText(normalized.phoneNumber())) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }
        if (hasCreateConflict(normalized)) {
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
        }

        try {
            User user = new User();
            applyCreateFields(user, normalized);
            User saved = userRepository.saveAndFlush(user);
            auditRepository.append(auditEvent(
                    IdentityAdminAction.CREATE,
                    normalized.actor(),
                    saved,
                    null,
                    normalized.reason(),
                    "{}",
                    snapshot(saved)
            ));
            return result(IdentityAdminStatus.CREATED, saved);
        } catch (DataIntegrityViolationException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult updateUser(Long userId, IdentityAdminCommand command) {
        IdentityAdminCommand normalized = normalize(command);
        if (userId == null || !isValid(normalized)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        Optional<User> lookup = userRepository.findByInternalId(userId);
        if (lookup.isEmpty()) {
            return result(IdentityAdminStatus.USER_NOT_FOUND, null);
        }
        User user = lookup.get();
        if (!Objects.equals(user.getPhoneNumber(), normalized.phoneNumber())) {
            return result(IdentityAdminStatus.INVALID_REQUEST, user);
        }
        if (hasUpdateConflict(userId, normalized)) {
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, user);
        }
        if (profileMatches(user, normalized)) {
            return result(IdentityAdminStatus.NO_CHANGE, user);
        }

        String before = snapshot(user);
        applyProfileFields(user, normalized);
        try {
            User saved = userRepository.saveAndFlush(user);
            auditRepository.append(auditEvent(
                    IdentityAdminAction.UPDATE_PROFILE,
                    normalized.actor(),
                    saved,
                    null,
                    normalized.reason(),
                    before,
                    snapshot(saved)
            ));
            return result(IdentityAdminStatus.UPDATED, saved);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult remapTelegram(
            Long sourceUserId,
            Long targetUserId,
            String actor,
            String reason
    ) {
        String normalizedActor = trimToNull(actor);
        String normalizedReason = trimToNull(reason);
        if (sourceUserId == null
                || targetUserId == null
                || !isValidContext(normalizedActor, normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        Optional<User> sourceLookup = userRepository.findByInternalId(sourceUserId);
        Optional<User> targetLookup = userRepository.findByInternalId(targetUserId);
        if (sourceLookup.isEmpty() || targetLookup.isEmpty()) {
            return result(IdentityAdminStatus.USER_NOT_FOUND, null);
        }
        User source = sourceLookup.get();
        User target = targetLookup.get();
        if (Objects.equals(sourceUserId, targetUserId) || source.getTelegramUserId() == null) {
            return result(IdentityAdminStatus.NO_CHANGE, target);
        }
        if (target.getTelegramUserId() != null) {
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, target);
        }

        String before = "source=" + snapshot(source) + "|target=" + snapshot(target);
        Long telegramUserId = source.getTelegramUserId();
        Long chatId = source.getChatId();
        String username = source.getUsername();
        String firstName = source.getFirstName();
        clearTelegramRouting(source);

        try {
            userRepository.saveAndFlush(source);
            target.setTelegramUserId(telegramUserId);
            target.setChatId(chatId);
            target.setUsername(username);
            target.setFirstName(firstName);
            User savedTarget = userRepository.saveAndFlush(target);
            String after = "source=" + snapshot(source) + "|target=" + snapshot(savedTarget);
            auditRepository.append(auditEvent(
                    IdentityAdminAction.REMAP_TELEGRAM,
                    normalizedActor,
                    savedTarget,
                    source,
                    normalizedReason,
                    before,
                    after
            ));
            return result(IdentityAdminStatus.REMAPPED, savedTarget);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult changeStatus(
            Long userId,
            UserStatus desiredStatus,
            String actor,
            String reason
    ) {
        String normalizedActor = trimToNull(actor);
        String normalizedReason = trimToNull(reason);
        if (userId == null
                || desiredStatus == null
                || !isValidContext(normalizedActor, normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        Optional<User> lookup = userRepository.findByInternalId(userId);
        if (lookup.isEmpty()) {
            return result(IdentityAdminStatus.USER_NOT_FOUND, null);
        }
        User user = lookup.get();
        if (user.getStatus() == desiredStatus) {
            return result(IdentityAdminStatus.NO_CHANGE, user);
        }

        String before = snapshot(user);
        user.setStatus(desiredStatus);
        IdentityAdminAction action = desiredStatus == UserStatus.ACTIVE
                ? IdentityAdminAction.REACTIVATE
                : IdentityAdminAction.DEACTIVATE;
        IdentityAdminStatus successStatus = desiredStatus == UserStatus.ACTIVE
                ? IdentityAdminStatus.REACTIVATED
                : IdentityAdminStatus.DEACTIVATED;
        try {
            User saved = userRepository.saveAndFlush(user);
            auditRepository.append(auditEvent(
                    action,
                    normalizedActor,
                    saved,
                    null,
                    normalizedReason,
                    before,
                    snapshot(saved)
            ));
            return result(successStatus, saved);
        } catch (OptimisticLockingFailureException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult updateUser(
            Long actorTelegramUserId,
            Long targetTelegramUserId,
            String employeeCode,
            String fullName,
            String unitName,
            String reason
    ) {
        String normalizedReason = trimToNull(reason);
        String normalizedEmployeeCode = normalizeEmployeeCode(employeeCode);
        String normalizedFullName = trimToNull(fullName);
        String normalizedUnitName = trimToNull(unitName);
        if (!isPositive(actorTelegramUserId)
                || !isPositive(targetTelegramUserId)
                || !isValidReason(normalizedReason)
                || !validLength(normalizedEmployeeCode)
                || !validLength(normalizedFullName)
                || !validLength(normalizedUnitName)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        try {
            MappedUsers users = findMappedUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return result(users.failure(), null);
            }
            if (!canManage(users.actor(), users.target())) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }

            User target = users.target();
            if (Objects.equals(target.getEmployeeCode(), normalizedEmployeeCode)
                    && Objects.equals(target.getFullName(), normalizedFullName)
                    && Objects.equals(target.getUnitName(), normalizedUnitName)) {
                return result(IdentityAdminStatus.NO_CHANGE, target);
            }
            if (normalizedEmployeeCode != null
                    && userRepository.existsByEmployeeCodeAndIdNot(normalizedEmployeeCode, target.getId())) {
                return result(IdentityAdminStatus.IDENTITY_CONFLICT, target);
            }
            String before = snapshot(target);
            target.setEmployeeCode(normalizedEmployeeCode);
            target.setFullName(normalizedFullName);
            target.setUnitName(normalizedUnitName);
            return persistProfileChange(
                    target,
                    actorLabel(users.actor()),
                    normalizedReason,
                    before
            );
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult changeStatus(
            Long actorTelegramUserId,
            Long targetTelegramUserId,
            UserStatus desiredStatus,
            String reason
    ) {
        String normalizedReason = trimToNull(reason);
        if (!isPositive(actorTelegramUserId)
                || !isPositive(targetTelegramUserId)
                || desiredStatus == null
                || !isValidReason(normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        try {
            MappedUsers users = findMappedUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return result(users.failure(), null);
            }
            if (!canManage(users.actor(), users.target())) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }
            return changeStatus(
                    users.target().getId(),
                    desiredStatus,
                    actorLabel(users.actor()),
                    normalizedReason
            );
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult editReport(
            Long actorTelegramUserId,
            Long reportId,
            String content,
            String reason
    ) {
        String normalizedContent = trimToNull(content);
        String normalizedReason = trimToNull(reason);
        if (!isPositive(actorTelegramUserId)
                || !isPositive(reportId)
                || normalizedContent == null
                || !isValidReason(normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        try {
            User actor = findMappedActor(actorTelegramUserId);
            if (!canManage(actor)) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }
            Optional<DailyReport> lookup = dailyReportRepository.findLockedById(reportId);
            if (lookup.isEmpty()) {
                return result(IdentityAdminStatus.REPORT_NOT_FOUND, null);
            }
            DailyReport report = lookup.get();
            User owner = report.getUser();
            if (!canManage(actor, owner)) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }
            if (Objects.equals(report.getContent(), normalizedContent)) {
                return result(IdentityAdminStatus.NO_CHANGE, owner);
            }

            String before = reportSnapshot(report);
            report.setContent(normalizedContent);
            try {
                dailyReportRepository.saveAndFlush(report);
                auditRepository.append(auditEvent(
                        IdentityAdminAction.UPDATE_REPORT,
                        actorLabel(actor),
                        owner,
                        null,
                        normalizedReason,
                        before,
                        reportSnapshot(report)
                ));
                return result(IdentityAdminStatus.UPDATED, owner);
            } catch (DataIntegrityViolationException exception) {
                markRollbackOnly();
                return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
            } catch (DataAccessException exception) {
                markRollbackOnly();
                return result(IdentityAdminStatus.FAILED, null);
            }
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult deleteReport(
            Long actorTelegramUserId,
            Long reportId,
            String reason
    ) {
        String normalizedReason = trimToNull(reason);
        if (!isPositive(actorTelegramUserId)
                || !isPositive(reportId)
                || !isValidReason(normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        try {
            User actor = findMappedActor(actorTelegramUserId);
            if (!canManage(actor)) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }
            Optional<DailyReport> lookup = dailyReportRepository.findLockedById(reportId);
            if (lookup.isEmpty()) {
                return result(IdentityAdminStatus.REPORT_NOT_FOUND, null);
            }
            DailyReport report = lookup.get();
            User owner = report.getUser();
            if (!canManage(actor, owner)) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }

            String before = reportSnapshot(report);
            try {
                dailyReportRepository.delete(report);
                dailyReportRepository.flush();
                auditRepository.append(auditEvent(
                        IdentityAdminAction.DELETE_REPORT,
                        actorLabel(actor),
                        owner,
                        null,
                        normalizedReason,
                        before,
                        "{reportId=" + report.getId() + ",deleted=true}"
                ));
                return result(IdentityAdminStatus.DELETED, owner);
            } catch (DataAccessException exception) {
                markRollbackOnly();
                return result(IdentityAdminStatus.FAILED, null);
            }
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional
    public IdentityAdminResult changeRole(
            Long actorTelegramUserId,
            Long targetTelegramUserId,
            Role role,
            RoleChange change,
            String reason
    ) {
        String normalizedReason = trimToNull(reason);
        if (!isPositive(actorTelegramUserId)
                || !isPositive(targetTelegramUserId)
                || role == null
                || change == null
                || !isValidReason(normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }

        try {
            MappedUsers users = findMappedAdminUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return result(users.failure(), null);
            }
            User actor = users.actor();
            User target = users.target();
            if (role == Role.MANAGER
                    && change == RoleChange.GRANT
                    && !StringUtils.hasText(target.getDepartmentName())
                    || role == Role.ADMIN
                    && change == RoleChange.REVOKE
                    && Objects.equals(actor.getId(), target.getId())) {
                return result(IdentityAdminStatus.ACCESS_DENIED, null);
            }

            boolean desired = change == RoleChange.GRANT;
            boolean current = role == Role.MANAGER ? target.isManager() : target.isAdmin();
            if (current == desired) {
                return result(IdentityAdminStatus.NO_CHANGE, target);
            }

            String before = roleSnapshot(target);
            if (role == Role.MANAGER) {
                target.setManager(desired);
            } else {
                target.setAdmin(desired);
            }
            try {
                User saved = userRepository.saveAndFlush(target);
                auditRepository.append(auditEvent(
                        roleAction(role, change),
                        actorLabel(actor),
                        saved,
                        null,
                        normalizedReason,
                        before,
                        roleSnapshot(saved)
                ));
                return result(IdentityAdminStatus.UPDATED, saved);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                markRollbackOnly();
                return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
            } catch (DataAccessException exception) {
                markRollbackOnly();
                return result(IdentityAdminStatus.FAILED, null);
            }
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readUsers(Long actorTelegramUserId) {
        if (!isPositive(actorTelegramUserId)) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            User actor = findMappedActor(actorTelegramUserId);
            if (!canManage(actor)) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            List<User> users = actor.isAdmin()
                    ? userRepository.findAll()
                    : userRepository.findCurrentTeamMembers(actor.getDepartmentName().trim(), null);
            return managementResult(
                    users.isEmpty() ? IdentityAdminStatus.NO_DATA : IdentityAdminStatus.SUCCESS,
                    users,
                    List.of(),
                    List.of()
            );
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readUser(Long actorTelegramUserId, Long targetTelegramUserId) {
        if (!isPositive(actorTelegramUserId) || !isPositive(targetTelegramUserId)) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            MappedUsers users = findMappedUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return managementResult(users.failure());
            }
            if (!canManage(users.actor(), users.target())) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            return managementResult(
                    IdentityAdminStatus.SUCCESS,
                    List.of(users.target()),
                    List.of(),
                    List.of()
            );
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readReports(
            Long actorTelegramUserId,
            Long targetTelegramUserId,
            LocalDate reportDate
    ) {
        if (!isPositive(actorTelegramUserId)
                || !isPositive(targetTelegramUserId)
                || reportDate == null) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            MappedUsers users = findMappedUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return managementResult(users.failure());
            }
            if (!canManage(users.actor(), users.target())) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            List<DailyReport> reports = dailyReportRepository
                    .findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(users.target().getId(), reportDate);
            return managementResult(
                    reports.isEmpty() ? IdentityAdminStatus.NO_DATA : IdentityAdminStatus.SUCCESS,
                    List.of(users.target()),
                    reports,
                    List.of()
            );
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readRecentReports(
            Long actorTelegramUserId,
            Long targetTelegramUserId
    ) {
        if (!isPositive(actorTelegramUserId) || !isPositive(targetTelegramUserId)) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            MappedUsers users = findMappedUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return managementResult(users.failure());
            }
            if (!canManage(users.actor(), users.target())) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            List<DailyReport> reports = dailyReportRepository
                    .findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(
                            targetTelegramUserId,
                            PageRequest.of(0, 5)
                    )
                    .stream()
                    .limit(5)
                    .toList();
            return managementResult(
                    reports.isEmpty() ? IdentityAdminStatus.NO_DATA : IdentityAdminStatus.SUCCESS,
                    List.of(users.target()),
                    reports,
                    List.of()
            );
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readReport(Long actorTelegramUserId, Long reportId) {
        if (!isPositive(actorTelegramUserId) || !isPositive(reportId)) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            User actor = findMappedActor(actorTelegramUserId);
            if (!canManage(actor)) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            Optional<DailyReport> lookup = dailyReportRepository.findById(reportId);
            if (lookup.isEmpty()) {
                return managementResult(IdentityAdminStatus.REPORT_NOT_FOUND);
            }
            DailyReport report = lookup.get();
            if (!canManage(actor, report.getUser())) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            return managementResult(
                    IdentityAdminStatus.SUCCESS,
                    List.of(report.getUser()),
                    List.of(report),
                    List.of()
            );
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readDepartmentReports(
            Long actorTelegramUserId,
            String departmentName,
            LocalDate reportDate
    ) {
        String normalizedDepartment = trimToNull(departmentName);
        if (!isPositive(actorTelegramUserId)
                || reportDate == null
                || normalizedDepartment == null
                || !validLength(normalizedDepartment)) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            User actor = findMappedActor(actorTelegramUserId);
            if (!canManage(actor)
                    || !actor.isAdmin()
                    && !actor.getDepartmentName().trim().equalsIgnoreCase(normalizedDepartment)) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            List<User> users = userRepository.findCurrentTeamMembers(normalizedDepartment, null);
            List<Long> userIds = users.stream()
                    .map(User::getId)
                    .filter(Objects::nonNull)
                    .toList();
            List<DailyReport> reports = userIds.isEmpty()
                    ? List.of()
                    : dailyReportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
                            userIds,
                            reportDate
                    );
            return managementResult(IdentityAdminStatus.SUCCESS, users, reports, List.of());
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readOrganizationReports(Long actorTelegramUserId, LocalDate reportDate) {
        if (!isPositive(actorTelegramUserId) || reportDate == null) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            User actor = findMappedActor(actorTelegramUserId);
            if (!isActiveAdmin(actor)) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            List<User> users = userRepository.findAll();
            List<Long> userIds = users.stream()
                    .map(User::getId)
                    .filter(Objects::nonNull)
                    .toList();
            List<DailyReport> reports = userIds.isEmpty()
                    ? List.of()
                    : dailyReportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
                            userIds,
                            reportDate
                    );
            return managementResult(IdentityAdminStatus.SUCCESS, users, reports, List.of());
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public ManagementReadResult readAudit(Long actorTelegramUserId, Long targetTelegramUserId) {
        if (!isPositive(actorTelegramUserId) || !isPositive(targetTelegramUserId)) {
            return managementResult(IdentityAdminStatus.INVALID_REQUEST);
        }
        try {
            MappedUsers users = findMappedUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return managementResult(users.failure());
            }
            if (!canManage(users.actor(), users.target())) {
                return managementResult(IdentityAdminStatus.ACCESS_DENIED);
            }
            List<IdentityAuditEvent> events = auditRepository.findHistory(users.target().getId(), 10)
                    .stream()
                    .limit(10)
                    .toList();
            return managementResult(
                    events.isEmpty() ? IdentityAdminStatus.NO_DATA : IdentityAdminStatus.SUCCESS,
                    List.of(users.target()),
                    List.of(),
                    events
            );
        } catch (DataAccessException exception) {
            return managementResult(IdentityAdminStatus.FAILED);
        }
    }

    @Transactional
    /**
     * Thay đổi phòng ban (department) của một nhân viên.
     * Kiểm tra quyền Admin của người thực hiện và ghi nhận sự thay đổi vào
     * lịch sử hệ thống (audit trail).
     */
    public IdentityAdminResult changeDepartment(
            Long actorTelegramUserId,
            Long targetTelegramUserId,
            String departmentName,
            String unitName,
            String reason
    ) {
        String normalizedDepartment = trimToNull(departmentName);
        String normalizedUnit = trimToNull(unitName);
        String normalizedReason = trimToNull(reason);
        if (!isPositive(actorTelegramUserId)
                || !isPositive(targetTelegramUserId)
                || normalizedDepartment == null
                || !validLength(normalizedDepartment)
                || !validLength(normalizedUnit)
                || !isValidReason(normalizedReason)) {
            return result(IdentityAdminStatus.INVALID_REQUEST, null);
        }
        try {
            MappedUsers users = findMappedAdminUsers(actorTelegramUserId, targetTelegramUserId);
            if (users.failure() != null) {
                return result(users.failure(), null);
            }
            User target = users.target();
            if (Objects.equals(target.getDepartmentName(), normalizedDepartment)
                    && Objects.equals(target.getUnitName(), normalizedUnit)) {
                return result(IdentityAdminStatus.NO_CHANGE, target);
            }
            String before = snapshot(target);
            target.setDepartmentName(normalizedDepartment);
            target.setUnitName(normalizedUnit);
            return persistProfileChange(
                    target,
                    actorLabel(users.actor()),
                    normalizedReason,
                    before
            );
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    @Transactional(readOnly = true)
    public Optional<User> findCurrent(Long userId) {
        return userId == null ? Optional.empty() : userRepository.findByInternalId(userId);
    }

    @Transactional(readOnly = true)
    public List<IdentityAuditEvent> findHistory(Long userId, int limit) {
        return userId == null || limit <= 0 ? List.of() : auditRepository.findHistory(userId, limit);
    }

    private User findMappedActor(Long telegramUserId) {
        return userRepository.findByTelegramUserId(telegramUserId).orElse(null);
    }

    private MappedUsers findMappedUsers(Long actorTelegramUserId, Long targetTelegramUserId) {
        User actor = findMappedActor(actorTelegramUserId);
        if (!canManage(actor)) {
            return MappedUsers.failed(IdentityAdminStatus.ACCESS_DENIED);
        }
        User target = userRepository.findByTelegramUserId(targetTelegramUserId).orElse(null);
        if (target == null) {
            return MappedUsers.failed(IdentityAdminStatus.USER_NOT_FOUND);
        }
        return new MappedUsers(actor, target, null);
    }

    private MappedUsers findMappedAdminUsers(Long actorTelegramUserId, Long targetTelegramUserId) {
        User actor = findMappedActor(actorTelegramUserId);
        if (!isActiveAdmin(actor)) {
            return MappedUsers.failed(IdentityAdminStatus.ACCESS_DENIED);
        }
        User target = userRepository.findByTelegramUserId(targetTelegramUserId).orElse(null);
        return target == null
                ? MappedUsers.failed(IdentityAdminStatus.USER_NOT_FOUND)
                : new MappedUsers(actor, target, null);
    }

    private boolean canManage(User actor) {
        return actor != null
                && actor.getStatus() == UserStatus.ACTIVE
                && (actor.isAdmin()
                || actor.isManager() && StringUtils.hasText(actor.getDepartmentName()));
    }

    private boolean canManage(User actor, User target) {
        return canManage(actor)
                && target != null
                && (actor.isAdmin() || sameDepartment(actor, target));
    }

    private boolean sameDepartment(User actor, User target) {
        return StringUtils.hasText(actor.getDepartmentName())
                && StringUtils.hasText(target.getDepartmentName())
                && actor.getDepartmentName().trim().equalsIgnoreCase(target.getDepartmentName().trim());
    }

    private boolean isActiveAdmin(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE && user.isAdmin();
    }

    private String actorLabel(User actor) {
        return "user:" + actor.getId();
    }

    private IdentityAdminResult persistProfileChange(
            User target,
            String actor,
            String reason,
            String before
    ) {
        try {
            User saved = userRepository.saveAndFlush(target);
            auditRepository.append(auditEvent(
                    IdentityAdminAction.UPDATE_PROFILE,
                    actor,
                    saved,
                    null,
                    reason,
                    before,
                    snapshot(saved)
            ));
            return result(IdentityAdminStatus.UPDATED, saved);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.IDENTITY_CONFLICT, null);
        } catch (DataAccessException exception) {
            markRollbackOnly();
            return result(IdentityAdminStatus.FAILED, null);
        }
    }

    private IdentityAdminAction roleAction(Role role, RoleChange change) {
        return switch (role) {
            case MANAGER -> change == RoleChange.GRANT
                    ? IdentityAdminAction.GRANT_MANAGER
                    : IdentityAdminAction.REVOKE_MANAGER;
            case ADMIN -> change == RoleChange.GRANT
                    ? IdentityAdminAction.GRANT_ADMIN
                    : IdentityAdminAction.REVOKE_ADMIN;
        };
    }

    private String roleSnapshot(User user) {
        return "{userId=" + user.getId()
                + ",manager=" + user.isManager()
                + ",admin=" + user.isAdmin()
                + "}";
    }

    private String reportSnapshot(DailyReport report) {
        String content = report.getContent();
        return "{reportId=" + report.getId()
                + ",ownerId=" + report.getUser().getId()
                + ",reportDate=" + report.getReportDate()
                + ",createdAt=" + report.getCreatedAt()
                + ",department=" + report.getDepartment()
                + ",unit=" + report.getUnit()
                + ",contentLength=" + content.length()
                + ",contentSha256=" + sha256(content)
                + "}";
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean isPositive(Long value) {
        return value != null && value > 0;
    }

    private boolean isValidReason(String reason) {
        return StringUtils.hasText(reason) && reason.length() <= MAX_REASON_LENGTH;
    }

    private boolean hasCreateConflict(IdentityAdminCommand command) {
        return command.telegramUserId() != null
                && userRepository.findByTelegramUserId(command.telegramUserId()).isPresent()
                || command.phoneNumber() != null
                && userRepository.findByPhoneNumber(command.phoneNumber()).isPresent()
                || command.employeeCode() != null
                && userRepository.findByEmployeeCode(command.employeeCode()).isPresent();
    }

    private boolean hasUpdateConflict(Long userId, IdentityAdminCommand command) {
        return command.employeeCode() != null
                && userRepository.existsByEmployeeCodeAndIdNot(command.employeeCode(), userId);
    }

    private void applyCreateFields(User user, IdentityAdminCommand command) {
        user.setTelegramUserId(command.telegramUserId());
        user.setChatId(command.chatId());
        user.setUsername(command.username());
        user.setFirstName(command.firstName());
        user.setPhoneNumber(command.phoneNumber());
        user.setStatus(command.status() == null ? UserStatus.ACTIVE : command.status());
        applyProfileFields(user, command);
    }

    private void applyProfileFields(User user, IdentityAdminCommand command) {
        user.setEmployeeCode(command.employeeCode());
        user.setFullName(command.fullName());
        user.setDepartmentName(command.departmentName());
        user.setUnitName(command.unitName());
    }

    private boolean profileMatches(User user, IdentityAdminCommand command) {
        return Objects.equals(user.getEmployeeCode(), command.employeeCode())
                && Objects.equals(user.getFullName(), command.fullName())
                && Objects.equals(user.getDepartmentName(), command.departmentName())
                && Objects.equals(user.getUnitName(), command.unitName());
    }

    private IdentityAuditEvent auditEvent(
            IdentityAdminAction action,
            String actor,
            User target,
            User related,
            String reason,
            String before,
            String after
    ) {
        return new IdentityAuditEvent(
                action,
                actor,
                target,
                related,
                reason,
                before,
                after,
                LocalDateTime.now(clock)
        );
    }

    private String snapshot(User user) {
        return "{userId=" + user.getId()
                + ",telegramUserId=" + user.getTelegramUserId()
                + ",employeeCode=" + user.getEmployeeCode()
                + ",phoneNumber=" + user.getPhoneNumber()
                + ",fullName=" + user.getFullName()
                + ",departmentName=" + user.getDepartmentName()
                + ",unitName=" + user.getUnitName()
                + ",status=" + user.getStatus()
                + "}";
    }

    private IdentityAdminCommand normalize(IdentityAdminCommand command) {
        if (command == null) {
            return null;
        }
        return new IdentityAdminCommand(
                command.telegramUserId(),
                command.chatId(),
                trimToNull(command.username()),
                trimToNull(command.firstName()),
                trimToNull(command.phoneNumber()),
                normalizeEmployeeCode(command.employeeCode()),
                trimToNull(command.fullName()),
                trimToNull(command.departmentName()),
                trimToNull(command.unitName()),
                command.status(),
                trimToNull(command.actor()),
                trimToNull(command.reason())
        );
    }

    private boolean isValid(IdentityAdminCommand command) {
        return command != null
                && isValidContext(command.actor(), command.reason())
                && validLength(command.username())
                && validLength(command.firstName())
                && validLength(command.phoneNumber())
                && validLength(command.employeeCode())
                && validLength(command.fullName())
                && validLength(command.departmentName())
                && validLength(command.unitName());
    }

    private boolean isValidContext(String actor, String reason) {
        return StringUtils.hasText(actor)
                && actor.length() <= MAX_ACTOR_LENGTH
                && StringUtils.hasText(reason)
                && reason.length() <= MAX_REASON_LENGTH;
    }

    private void clearTelegramRouting(User user) {
        user.setTelegramUserId(null);
        user.setChatId(null);
        user.setUsername(null);
        user.setFirstName(null);
    }

    private boolean validLength(String value) {
        return value == null || value.length() <= MAX_PROFILE_LENGTH;
    }

    private String normalizeEmployeeCode(String employeeCode) {
        String normalized = trimToNull(employeeCode);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private IdentityAdminResult result(IdentityAdminStatus status, User user) {
        return new IdentityAdminResult(status, user);
    }

    private ManagementReadResult managementResult(IdentityAdminStatus status) {
        return managementResult(status, List.of(), List.of(), List.of());
    }

    private ManagementReadResult managementResult(
            IdentityAdminStatus status,
            List<User> users,
            List<DailyReport> reports,
            List<IdentityAuditEvent> auditEvents
    ) {
        return new ManagementReadResult(status, users, reports, auditEvents);
    }

    private void markRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private record MappedUsers(User actor, User target, IdentityAdminStatus failure) {
        private static MappedUsers failed(IdentityAdminStatus failure) {
            return new MappedUsers(null, null, failure);
        }
    }

}
