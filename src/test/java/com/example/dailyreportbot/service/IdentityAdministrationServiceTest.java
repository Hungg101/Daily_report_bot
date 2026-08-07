package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.IdentityAdminAction;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.IdentityAuditEventRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdentityAdministrationServiceTest {

    private UserRepository userRepository;
    private DailyReportRepository dailyReportRepository;
    private IdentityAuditEventRepository auditRepository;
    private IdentityAdministrationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        dailyReportRepository = mock(DailyReportRepository.class);
        auditRepository = mock(IdentityAuditEventRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-02T02:00:00Z"), ZoneOffset.UTC);
        service = new IdentityAdministrationService(
                userRepository,
                dailyReportRepository,
                auditRepository,
                clock
        );
    }

    @Test
    void shouldCreateNormalizedManagedUserWithOnePrivacyBoundedAuditEvent() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        String sensitiveReason = "report content message content token password secret";

        IdentityAdminResult result = service.createUser(command(" emp-01 ", "Engineering", sensitiveReason));

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.CREATED);
        assertThat(result.user()).isNotNull();
        assertThat(result.user().getEmployeeCode()).isEqualTo("EMP-01");
        assertThat(result.user().getDepartmentName()).isEqualTo("Engineering");
        ArgumentCaptor<IdentityAuditEvent> auditCaptor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(auditCaptor.capture());
        IdentityAuditEvent event = auditCaptor.getValue();
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.CREATE);
        assertThat(event.getActor()).isEqualTo("internal-ops");
        assertThat(event.getReason()).isEqualTo(sensitiveReason);
        assertThat((event.getBeforeState() + event.getAfterState()).toLowerCase())
                .doesNotContain("report content", "message content", "token", "password", "secret");
    }

    @Test
    void shouldRejectMissingGovernanceContextWithoutMutation() {
        IdentityAdminCommand command = command("EMP-01", "Engineering", " ");

        assertThat(service.createUser(command).status()).isEqualTo(IdentityAdminStatus.INVALID_REQUEST);

        verifyNoInteractions(userRepository, auditRepository);
    }

    @Test
    void shouldRejectCreateConflictWithoutPartialMutation() {
        User owner = new User();
        owner.setId(1L);
        when(userRepository.findByEmployeeCode("EMP-01")).thenReturn(Optional.of(owner));

        assertThat(service.createUser(command(" emp-01 ", "Engineering", "create")).status())
                .isEqualTo(IdentityAdminStatus.IDENTITY_CONFLICT);

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldUpdateGovernedProfileWithoutChangingTelegramRouting() {
        User existing = managedUser();
        existing.setTelegramUserId(12345L);
        existing.setChatId(1001L);
        existing.setUsername("telegram-user");
        existing.setFirstName("An");
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(existing)).thenReturn(existing);

        IdentityAdminResult result = service.updateUser(
                7L,
                command(" emp-02 ", "Platform", "profile correction")
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(existing.getEmployeeCode()).isEqualTo("EMP-02");
        assertThat(existing.getDepartmentName()).isEqualTo("Platform");
        assertThat(existing.getTelegramUserId()).isEqualTo(12345L);
        assertThat(existing.getChatId()).isEqualTo(1001L);
        assertThat(existing.getUsername()).isEqualTo("telegram-user");
        assertThat(existing.getFirstName()).isEqualTo("An");
        verify(auditRepository).append(any(IdentityAuditEvent.class));
    }

    @Test
    void shouldReturnNoChangeWithoutAudit() {
        User existing = managedUser();
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(existing));

        IdentityAdminResult result = service.updateUser(
                7L,
                command("EMP-01", "Engineering", "confirm profile")
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.NO_CHANGE);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldReturnFailedWhenAuditPersistenceFails() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(7L);
            return user;
        });
        when(auditRepository.append(any(IdentityAuditEvent.class)))
                .thenThrow(new DataAccessResourceFailureException("audit unavailable"));

        assertThat(service.createUser(command("EMP-01", "Engineering", "create")).status())
                .isEqualTo(IdentityAdminStatus.FAILED);
    }

    @Test
    void shouldExposeCurrentStateAndBoundedHistoryAsReadOnlyOperations() {
        User user = managedUser();
        IdentityAuditEvent event = mock(IdentityAuditEvent.class);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(auditRepository.findHistory(7L, 5)).thenReturn(List.of(event));

        assertThat(service.findCurrent(7L)).containsSame(user);
        assertThat(service.findHistory(7L, 5)).containsExactly(event);
        assertThat(service.findHistory(7L, 0)).isEmpty();
        verify(auditRepository).findHistory(7L, 5);
    }

    @Test
    void shouldRemapTelegramRoutingFieldsWithoutMovingGovernedProfile() {
        User source = routingUser(7L, 12345L);
        User target = routingUser(8L, null);
        target.setEmployeeCode("EMP-02");
        target.setDepartmentName("Platform");
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(source));
        when(userRepository.findByInternalId(8L)).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IdentityAdminResult result = service.remapTelegram(7L, 8L, "internal-ops", "correct mapping");

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.REMAPPED);
        assertThat(source.getTelegramUserId()).isNull();
        assertThat(source.getChatId()).isNull();
        assertThat(source.getUsername()).isNull();
        assertThat(source.getFirstName()).isNull();
        assertThat(source.getEmployeeCode()).isEqualTo("EMP-01");
        assertThat(target.getTelegramUserId()).isEqualTo(12345L);
        assertThat(target.getChatId()).isEqualTo(1001L);
        assertThat(target.getUsername()).isEqualTo("telegram-user");
        assertThat(target.getFirstName()).isEqualTo("An");
        assertThat(target.getEmployeeCode()).isEqualTo("EMP-02");
        assertThat(target.getDepartmentName()).isEqualTo("Platform");
        ArgumentCaptor<IdentityAuditEvent> captor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(IdentityAdminAction.REMAP_TELEGRAM);
        assertThat(captor.getValue().getTargetUser()).isSameAs(target);
        assertThat(captor.getValue().getRelatedUser()).isSameAs(source);
    }

    @Test
    void shouldRejectRemapWhenTargetAlreadyHasTelegramIdentity() {
        User source = routingUser(7L, 12345L);
        User target = routingUser(8L, 67890L);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(source));
        when(userRepository.findByInternalId(8L)).thenReturn(Optional.of(target));

        assertThat(service.remapTelegram(7L, 8L, "internal-ops", "correct mapping").status())
                .isEqualTo(IdentityAdminStatus.IDENTITY_CONFLICT);
        assertThat(source.getTelegramUserId()).isEqualTo(12345L);
        assertThat(target.getTelegramUserId()).isEqualTo(67890L);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldTreatSameUserRemapAsNoChange() {
        User source = routingUser(7L, 12345L);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(source));

        assertThat(service.remapTelegram(7L, 7L, "internal-ops", "confirm mapping").status())
                .isEqualTo(IdentityAdminStatus.NO_CHANGE);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldReturnFailedAndRequestRollbackWhenRemapAuditFails() {
        User source = routingUser(7L, 12345L);
        User target = routingUser(8L, null);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(source));
        when(userRepository.findByInternalId(8L)).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditRepository.append(any(IdentityAuditEvent.class)))
                .thenThrow(new DataAccessResourceFailureException("audit unavailable"));

        assertThat(service.remapTelegram(7L, 8L, "internal-ops", "correct mapping").status())
                .isEqualTo(IdentityAdminStatus.FAILED);
    }

    @Test
    void shouldDeactivateAndAuditActiveUser() {
        User user = managedUser();
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        IdentityAdminResult result = service.changeStatus(
                7L, UserStatus.INACTIVE, "internal-ops", "leave of absence"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.DEACTIVATED);
        assertThat(result.user()).isSameAs(user);
        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
        ArgumentCaptor<IdentityAuditEvent> captor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(IdentityAdminAction.DEACTIVATE);
    }

    @Test
    void shouldReactivateSameUserIdentity() {
        User user = managedUser();
        user.setStatus(UserStatus.INACTIVE);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        IdentityAdminResult result = service.changeStatus(
                7L, UserStatus.ACTIVE, "internal-ops", "return to work"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.REACTIVATED);
        assertThat(result.user().getId()).isEqualTo(7L);
        assertThat(result.user().getStatus()).isEqualTo(UserStatus.ACTIVE);
        ArgumentCaptor<IdentityAuditEvent> captor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(IdentityAdminAction.REACTIVATE);
    }

    @Test
    void shouldTreatRepeatedLifecycleRequestAsNoChange() {
        User user = managedUser();
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));

        assertThat(service.changeStatus(
                7L, UserStatus.ACTIVE, "internal-ops", "confirm active"
        ).status()).isEqualTo(IdentityAdminStatus.NO_CHANGE);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldReturnFailedAndRequestRollbackWhenLifecycleAuditFails() {
        User user = managedUser();
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        when(auditRepository.append(any(IdentityAuditEvent.class)))
                .thenThrow(new DataAccessResourceFailureException("audit unavailable"));

        assertThat(service.changeStatus(
                7L, UserStatus.INACTIVE, "internal-ops", "leave of absence"
        ).status()).isEqualTo(IdentityAdminStatus.FAILED);
    }

    @Test
    void shouldTranslateStaleProfileUpdateToIdentityConflict() {
        User user = managedUser();
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenThrow(
                new ObjectOptimisticLockingFailureException(User.class, 7L)
        );

        IdentityAdminResult result = service.updateUser(
                7L,
                command("EMP-02", "Platform", "concurrent profile correction")
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.IDENTITY_CONFLICT);
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldTranslateStaleLifecycleChangeToIdentityConflict() {
        User user = managedUser();
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenThrow(
                new ObjectOptimisticLockingFailureException(User.class, 7L)
        );

        IdentityAdminResult result = service.changeStatus(
                7L,
                UserStatus.INACTIVE,
                "internal-ops",
                "concurrent lifecycle change"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.IDENTITY_CONFLICT);
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldTranslateStaleTelegramRemapToIdentityConflict() {
        User source = routingUser(7L, 12345L);
        User target = routingUser(8L, null);
        when(userRepository.findByInternalId(7L)).thenReturn(Optional.of(source));
        when(userRepository.findByInternalId(8L)).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(source)).thenThrow(
                new ObjectOptimisticLockingFailureException(User.class, 7L)
        );

        IdentityAdminResult result = service.remapTelegram(
                7L,
                8L,
                "internal-ops",
                "concurrent remap"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.IDENTITY_CONFLICT);
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldAllowSameDepartmentManagerProfileUpdateAndPreserveForbiddenFields() {
        long actorTelegramId = 1101L;
        long targetTelegramId = 2202L;
        User actor = authorizedUser(1L, actorTelegramId, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(2L, targetTelegramId, "Engineering", false, true, UserStatus.ACTIVE);
        target.setEmployeeCode("EMP-OLD");
        target.setFullName("Old Name");
        target.setUnitName("Legacy Unit");
        stubMappedUser(actor);
        stubMappedUser(target);
        when(userRepository.saveAndFlush(target)).thenReturn(target);

        IdentityAdminResult result = service.updateUser(
                actorTelegramId,
                targetTelegramId,
                "EMP-NEW",
                "New Name",
                "Delivery",
                "correct profile"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(target.getEmployeeCode()).isEqualTo("EMP-NEW");
        assertThat(target.getFullName()).isEqualTo("New Name");
        assertThat(target.getUnitName()).isEqualTo("Delivery");
        assertThat(target.getTelegramUserId()).isEqualTo(targetTelegramId);
        assertThat(target.getChatId()).isEqualTo(targetTelegramId + 10_000L);
        assertThat(target.getUsername()).isEqualTo("telegram-user-2");
        assertThat(target.getFirstName()).isEqualTo("User 2");
        assertThat(target.getPhoneNumber()).isEqualTo("+84900000002");
        assertThat(target.getDepartmentName()).isEqualTo("Engineering");
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(target.isAdmin()).isTrue();
        assertThat(target.isManager()).isFalse();
        ArgumentCaptor<IdentityAuditEvent> event = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(IdentityAdminAction.UPDATE_PROFILE);
        assertThat(event.getValue().getActor()).isEqualTo("user:" + actor.getId());
        assertThat(event.getValue().getReason()).isEqualTo("correct profile");
        verifyNoInteractions(dailyReportRepository);
    }

    @Test
    void shouldDenyCrossDepartmentManagerProfileUpdateBeforeDelegateOrAudit() {
        long actorTelegramId = 1101L;
        long targetTelegramId = 2202L;
        User actor = authorizedUser(1L, actorTelegramId, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(2L, targetTelegramId, "Finance", false, false, UserStatus.ACTIVE);
        stubMappedUser(actor);
        stubMappedUser(target);
        IdentityAdminResult result = service.updateUser(
                actorTelegramId,
                targetTelegramId,
                "EMP-NEW",
                "New Name",
                "Delivery",
                "correct profile"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    @Test
    void shouldAllowGlobalAdminReportEditWithoutChangingImmutableMetadataOrAuditingBodies() {
        long actorTelegramId = 1101L;
        String oldContent = "old private report body";
        String newContent = "new private report body";
        User actor = authorizedUser(1L, actorTelegramId, null, false, true, UserStatus.ACTIVE);
        User owner = authorizedUser(2L, 2202L, "Finance", false, false, UserStatus.ACTIVE);
        DailyReport report = report(77L, owner, oldContent);
        LocalDate reportDate = report.getReportDate();
        LocalDateTime createdAt = report.getCreatedAt();
        String department = report.getDepartment();
        String unit = report.getUnit();
        String collaborators = report.getCollaborators();
        stubMappedUser(actor);
        when(dailyReportRepository.findLockedById(report.getId())).thenReturn(Optional.of(report));
        when(dailyReportRepository.saveAndFlush(report)).thenReturn(report);

        IdentityAdminResult result = service.editReport(
                actorTelegramId,
                report.getId(),
                newContent,
                "remove mistaken detail"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(report.getContent()).isEqualTo(newContent);
        assertThat(report.getId()).isEqualTo(77L);
        assertThat(report.getUser()).isSameAs(owner);
        assertThat(report.getReportDate()).isEqualTo(reportDate);
        assertThat(report.getCreatedAt()).isEqualTo(createdAt);
        assertThat(report.getDepartment()).isEqualTo(department);
        assertThat(report.getUnit()).isEqualTo(unit);
        assertThat(report.getCollaborators()).isEqualTo(collaborators);
        verify(dailyReportRepository).saveAndFlush(report);
        ArgumentCaptor<IdentityAuditEvent> captor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(captor.capture());
        IdentityAuditEvent event = captor.getValue();
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.UPDATE_REPORT);
        assertThat(event.getActor()).isEqualTo("user:" + actor.getId());
        assertThat(event.getTargetUser()).isSameAs(owner);
        assertThat(event.getReason()).isEqualTo("remove mistaken detail");
        assertThat(event.getBeforeState())
                .contains("contentLength=" + oldContent.length(), "contentSha256=" + sha256(oldContent))
                .doesNotContain(oldContent, newContent);
        assertThat(event.getAfterState())
                .contains("contentLength=" + newContent.length(), "contentSha256=" + sha256(newContent))
                .doesNotContain(oldContent, newContent);
        assertThat(event.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 2, 2, 0));
    }

    @Test
    void shouldAllowSameDepartmentManagerToHardDeleteReportWithPrivacySafeAudit() {
        long actorTelegramId = 1101L;
        String content = "private report body to delete";
        User actor = authorizedUser(1L, actorTelegramId, "Engineering", true, false, UserStatus.ACTIVE);
        User owner = authorizedUser(2L, 2202L, "Engineering", false, false, UserStatus.ACTIVE);
        DailyReport report = report(77L, owner, content);
        stubMappedUser(actor);
        when(dailyReportRepository.findLockedById(report.getId())).thenReturn(Optional.of(report));

        IdentityAdminResult result = service.deleteReport(
                actorTelegramId,
                report.getId(),
                "duplicate submission"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.DELETED);
        verify(dailyReportRepository).delete(report);
        ArgumentCaptor<IdentityAuditEvent> captor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(captor.capture());
        IdentityAuditEvent event = captor.getValue();
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.DELETE_REPORT);
        assertThat(event.getActor()).isEqualTo("user:" + actor.getId());
        assertThat(event.getTargetUser()).isSameAs(owner);
        assertThat(event.getBeforeState())
                .contains("contentLength=" + content.length(), "contentSha256=" + sha256(content))
                .doesNotContain(content);
        assertThat(event.getAfterState())
                .contains("reportId=" + report.getId(), "deleted=true")
                .doesNotContain(content);
    }

    @Test
    void shouldDenyManagerRoleMutationWithoutBusinessOrAuditWrite() {
        long actorTelegramId = 1101L;
        long targetTelegramId = 2202L;
        User actor = authorizedUser(1L, actorTelegramId, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(2L, targetTelegramId, "Engineering", false, false, UserStatus.ACTIVE);
        stubMappedUser(actor);
        stubMappedUser(target);

        IdentityAdminResult result = service.changeRole(
                actorTelegramId,
                targetTelegramId,
                IdentityAdministrationService.Role.ADMIN,
                IdentityAdministrationService.RoleChange.GRANT,
                "temporary administration"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(target.isAdmin()).isFalse();
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    @Test
    void shouldAllowAdminToGrantManagerToInactiveTargetWithoutActivatingIt() {
        long actorTelegramId = 1101L;
        long targetTelegramId = 2202L;
        User actor = authorizedUser(1L, actorTelegramId, null, false, true, UserStatus.ACTIVE);
        User target = authorizedUser(2L, targetTelegramId, "Engineering", false, false, UserStatus.INACTIVE);
        stubMappedUser(actor);
        stubMappedUser(target);
        when(userRepository.saveAndFlush(target)).thenReturn(target);

        IdentityAdminResult result = service.changeRole(
                actorTelegramId,
                targetTelegramId,
                IdentityAdministrationService.Role.MANAGER,
                IdentityAdministrationService.RoleChange.GRANT,
                "prepare next manager"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(result.user()).isSameAs(target);
        assertThat(target.isManager()).isTrue();
        assertThat(target.isAdmin()).isFalse();
        assertThat(target.getStatus()).isEqualTo(UserStatus.INACTIVE);
        verify(userRepository).saveAndFlush(target);
        ArgumentCaptor<IdentityAuditEvent> captor = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(captor.capture());
        IdentityAuditEvent event = captor.getValue();
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.GRANT_MANAGER);
        assertThat(event.getActor()).isEqualTo("user:" + actor.getId());
        assertThat(event.getTargetUser()).isSameAs(target);
        assertThat(event.getBeforeState()).contains("manager=false", "admin=false");
        assertThat(event.getAfterState()).contains("manager=true", "admin=false");
    }

    @Test
    void shouldDenyManagerGrantWhenTargetHasNoDepartment() {
        long actorTelegramId = 1101L;
        long targetTelegramId = 2202L;
        User actor = authorizedUser(1L, actorTelegramId, null, false, true, UserStatus.ACTIVE);
        User target = authorizedUser(2L, targetTelegramId, " ", false, false, UserStatus.INACTIVE);
        stubMappedUser(actor);
        stubMappedUser(target);

        IdentityAdminResult result = service.changeRole(
                actorTelegramId,
                targetTelegramId,
                IdentityAdministrationService.Role.MANAGER,
                IdentityAdministrationService.RoleChange.GRANT,
                "prepare next manager"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(target.isManager()).isFalse();
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    @Test
    void shouldDenySelfAdminRevocation() {
        long actorTelegramId = 1101L;
        User actor = authorizedUser(1L, actorTelegramId, null, false, true, UserStatus.ACTIVE);
        stubMappedUser(actor);

        IdentityAdminResult result = service.changeRole(
                actorTelegramId,
                actorTelegramId,
                IdentityAdministrationService.Role.ADMIN,
                IdentityAdministrationService.RoleChange.REVOKE,
                "remove own access"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(actor.isAdmin()).isTrue();
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    @Test
    void shouldReadManagerScopedUsersAndExactDateReportsWithoutWrites() {
        User manager = authorizedUser(10L, 10_010L, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(11L, 10_011L, "engineering", false, false, UserStatus.INACTIVE);
        LocalDate reportDate = LocalDate.of(2026, 7, 2);
        DailyReport first = report(101L, target, "first");
        DailyReport second = report(102L, target, "second");
        first.setReportDate(reportDate);
        second.setReportDate(reportDate);
        when(userRepository.findByTelegramUserId(manager.getTelegramUserId())).thenReturn(Optional.of(manager));
        when(userRepository.findByTelegramUserId(target.getTelegramUserId())).thenReturn(Optional.of(target));
        when(userRepository.findCurrentTeamMembers("Engineering", null)).thenReturn(List.of(manager, target));
        when(dailyReportRepository.findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(
                target.getId(), reportDate
        )).thenReturn(List.of(second, first));

        ManagementReadResult users = service.readUsers(manager.getTelegramUserId());
        ManagementReadResult reports = service.readReports(
                manager.getTelegramUserId(), target.getTelegramUserId(), reportDate
        );

        assertThat(users.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(users.users()).containsExactly(manager, target);
        assertThat(reports.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(reports.users()).containsExactly(target);
        assertThat(reports.reports()).containsExactly(second, first);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(dailyReportRepository, never()).saveAndFlush(any(DailyReport.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldEnforcePersistedActorScopeAndAdminPrecedenceForReads() {
        User manager = authorizedUser(20L, 20_020L, "Engineering", true, false, UserStatus.ACTIVE);
        User outsider = authorizedUser(21L, 20_021L, "Finance", false, false, UserStatus.ACTIVE);
        User inactiveAdmin = authorizedUser(22L, 20_022L, null, false, true, UserStatus.INACTIVE);
        User managerWithoutDepartment = authorizedUser(23L, 20_023L, " ", true, false, UserStatus.ACTIVE);
        User employee = authorizedUser(24L, 20_024L, "Engineering", false, false, UserStatus.ACTIVE);
        User dualRoleAdmin = authorizedUser(25L, 20_025L, null, true, true, UserStatus.ACTIVE);
        for (User user : List.of(manager, outsider, inactiveAdmin, managerWithoutDepartment, employee, dualRoleAdmin)) {
            when(userRepository.findByTelegramUserId(user.getTelegramUserId())).thenReturn(Optional.of(user));
        }

        assertThat(service.readUser(manager.getTelegramUserId(), outsider.getTelegramUserId()).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.readUsers(inactiveAdmin.getTelegramUserId()).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.readUsers(managerWithoutDepartment.getTelegramUserId()).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.readUsers(employee.getTelegramUserId()).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.readUser(99_999L, outsider.getTelegramUserId()).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        ManagementReadResult adminRead = service.readUser(
                dualRoleAdmin.getTelegramUserId(), outsider.getTelegramUserId()
        );
        assertThat(adminRead.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(adminRead.users()).containsExactly(outsider);
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    @Test
    void shouldBoundRecentReportsAndAuditAndAllowOnlyAdminOrganizationRead() {
        User admin = authorizedUser(30L, 30_030L, null, false, true, UserStatus.ACTIVE);
        User manager = authorizedUser(31L, 30_031L, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(32L, 30_032L, "Engineering", false, false, UserStatus.ACTIVE);
        List<DailyReport> reports = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> report(200L + index, target, "report-" + index))
                .toList();
        List<IdentityAuditEvent> events = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> new IdentityAuditEvent(
                        IdentityAdminAction.UPDATE_PROFILE,
                        "user:30",
                        target,
                        null,
                        "reason-" + index,
                        "before",
                        "after",
                        LocalDateTime.of(2026, 7, 2, 10, index)
                ))
                .toList();
        when(userRepository.findByTelegramUserId(admin.getTelegramUserId())).thenReturn(Optional.of(admin));
        when(userRepository.findByTelegramUserId(manager.getTelegramUserId())).thenReturn(Optional.of(manager));
        when(userRepository.findByTelegramUserId(target.getTelegramUserId())).thenReturn(Optional.of(target));
        when(userRepository.findAll()).thenReturn(List.of(admin, manager, target));
        when(dailyReportRepository.findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(
                target.getTelegramUserId(), PageRequest.of(0, 5)
        )).thenReturn(reports.subList(0, 5));
        when(dailyReportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
                any(), eq(LocalDate.of(2026, 7, 2))
        )).thenReturn(reports);
        when(auditRepository.findHistory(target.getId(), 10)).thenReturn(events);

        ManagementReadResult recent = service.readRecentReports(
                manager.getTelegramUserId(), target.getTelegramUserId()
        );
        ManagementReadResult audit = service.readAudit(admin.getTelegramUserId(), target.getTelegramUserId());
        ManagementReadResult organization = service.readOrganizationReports(
                admin.getTelegramUserId(), LocalDate.of(2026, 7, 2)
        );

        assertThat(recent.reports()).hasSize(5);
        assertThat(audit.auditEvents()).hasSize(10);
        assertThat(organization.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(organization.users()).containsExactly(admin, manager, target);
        assertThat(service.readOrganizationReports(
                manager.getTelegramUserId(), LocalDate.of(2026, 7, 2)
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
    }

    @Test
    void shouldAllowOnlyActiveAdminToTransferDepartmentWithOneAudit() {
        User admin = authorizedUser(40L, 40_040L, null, false, true, UserStatus.ACTIVE);
        User manager = authorizedUser(41L, 40_041L, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(42L, 40_042L, "Engineering", true, true, UserStatus.INACTIVE);
        when(userRepository.findByTelegramUserId(admin.getTelegramUserId())).thenReturn(Optional.of(admin));
        when(userRepository.findByTelegramUserId(manager.getTelegramUserId())).thenReturn(Optional.of(manager));
        when(userRepository.findByTelegramUserId(target.getTelegramUserId())).thenReturn(Optional.of(target));
        when(userRepository.findByInternalId(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(target)).thenReturn(target);

        IdentityAdminResult updated = service.changeDepartment(
                admin.getTelegramUserId(), target.getTelegramUserId(), " Finance ", " Core ", "transfer"
        );

        assertThat(updated.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(target.getDepartmentName()).isEqualTo("Finance");
        assertThat(target.getUnitName()).isEqualTo("Core");
        assertThat(target.isManager()).isTrue();
        assertThat(target.isAdmin()).isTrue();
        assertThat(target.getStatus()).isEqualTo(UserStatus.INACTIVE);
        ArgumentCaptor<IdentityAuditEvent> event = ArgumentCaptor.forClass(IdentityAuditEvent.class);
        verify(auditRepository).append(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(IdentityAdminAction.UPDATE_PROFILE);
        assertThat(event.getValue().getBeforeState()).contains("departmentName=Engineering");
        assertThat(event.getValue().getAfterState()).contains("departmentName=Finance");

        assertThat(service.changeDepartment(
                manager.getTelegramUserId(), target.getTelegramUserId(), "Sales", null, "denied"
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void shouldDenyIneligibleAndManagerOnlyActorsBeforeLookingUpAdminOnlyTargets() {
        User employee = authorizedUser(50L, 50_050L, "Engineering", false, false, UserStatus.ACTIVE);
        User manager = authorizedUser(51L, 50_051L, "Engineering", true, false, UserStatus.ACTIVE);
        when(userRepository.findByTelegramUserId(employee.getTelegramUserId())).thenReturn(Optional.of(employee));
        when(userRepository.findByTelegramUserId(manager.getTelegramUserId())).thenReturn(Optional.of(manager));

        assertThat(service.readUser(employee.getTelegramUserId(), 88_888L).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.changeRole(
                manager.getTelegramUserId(),
                88_888L,
                IdentityAdministrationService.Role.ADMIN,
                IdentityAdministrationService.RoleChange.GRANT,
                "denied"
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.changeDepartment(
                manager.getTelegramUserId(), 88_888L, "Finance", null, "denied"
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        verify(userRepository, never()).findByTelegramUserId(88_888L);
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    @Test
    void shouldPreserveDepartmentExactlyDuringManagerProfileUpdate() {
        User manager = authorizedUser(60L, 60_060L, "Engineering", true, false, UserStatus.ACTIVE);
        User target = authorizedUser(61L, 60_061L, " Engineering ", false, false, UserStatus.ACTIVE);
        when(userRepository.findByTelegramUserId(manager.getTelegramUserId())).thenReturn(Optional.of(manager));
        when(userRepository.findByTelegramUserId(target.getTelegramUserId())).thenReturn(Optional.of(target));
        when(userRepository.findByInternalId(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(target)).thenReturn(target);

        IdentityAdminResult result = service.updateUser(
                manager.getTelegramUserId(),
                target.getTelegramUserId(),
                "EMP-NEW",
                "New Name",
                "New Unit",
                "profile update"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(target.getDepartmentName()).isEqualTo(" Engineering ");
    }

    @Test
    void shouldPreserveUnrelatedProfileFieldsDuringDepartmentTransfer() {
        User admin = authorizedUser(70L, 70_070L, null, false, true, UserStatus.ACTIVE);
        User target = authorizedUser(71L, 70_071L, "Engineering", true, true, UserStatus.INACTIVE);
        target.setEmployeeCode(" EMP-RAW ");
        target.setFullName(" Full Name Raw ");
        when(userRepository.findByTelegramUserId(admin.getTelegramUserId())).thenReturn(Optional.of(admin));
        when(userRepository.findByTelegramUserId(target.getTelegramUserId())).thenReturn(Optional.of(target));
        when(userRepository.findByInternalId(target.getId())).thenReturn(Optional.of(target));
        when(userRepository.saveAndFlush(target)).thenReturn(target);

        IdentityAdminResult result = service.changeDepartment(
                admin.getTelegramUserId(), target.getTelegramUserId(), "Finance", "Core", "transfer"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        assertThat(target.getEmployeeCode()).isEqualTo(" EMP-RAW ");
        assertThat(target.getFullName()).isEqualTo(" Full Name Raw ");
        assertThat(target.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(target.isManager()).isTrue();
        assertThat(target.isAdmin()).isTrue();
    }

    @Test
    void shouldAuthorizeReportDetailFromPersistedOwnerScope() {
        User manager = authorizedUser(80L, 80_080L, "Engineering", true, false, UserStatus.ACTIVE);
        User otherManager = authorizedUser(81L, 80_081L, "Finance", true, false, UserStatus.ACTIVE);
        User admin = authorizedUser(82L, 80_082L, null, false, true, UserStatus.ACTIVE);
        User inactiveOwner = authorizedUser(83L, 80_083L, " engineering ", false, false, UserStatus.INACTIVE);
        DailyReport report = report(801L, inactiveOwner, "authorized detail");
        for (User actor : List.of(manager, otherManager, admin)) {
            when(userRepository.findByTelegramUserId(actor.getTelegramUserId())).thenReturn(Optional.of(actor));
        }
        when(dailyReportRepository.findById(report.getId())).thenReturn(Optional.of(report));

        assertThat(service.readReport(99_999L, 899L).status())
                .isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        verify(dailyReportRepository, never()).findById(899L);
        assertThat(service.readReport(manager.getTelegramUserId(), 898L).status())
                .isEqualTo(IdentityAdminStatus.REPORT_NOT_FOUND);

        ManagementReadResult managerRead = service.readReport(manager.getTelegramUserId(), report.getId());
        ManagementReadResult deniedRead = service.readReport(otherManager.getTelegramUserId(), report.getId());
        ManagementReadResult adminRead = service.readReport(admin.getTelegramUserId(), report.getId());

        assertThat(managerRead.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(managerRead.users()).containsExactly(inactiveOwner);
        assertThat(managerRead.reports()).containsExactly(report);
        assertThat(deniedRead.status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(adminRead.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(adminRead.reports()).containsExactly(report);
        verify(dailyReportRepository, never()).saveAndFlush(any(DailyReport.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldAuthorizeDepartmentReadsFromPersistedActorDepartment() {
        LocalDate reportDate = LocalDate.of(2026, 7, 2);
        User manager = authorizedUser(90L, 90_090L, " Engineering ", true, false, UserStatus.ACTIVE);
        User inactiveManager = authorizedUser(91L, 90_091L, "Engineering", true, false, UserStatus.INACTIVE);
        User managerWithoutDepartment = authorizedUser(92L, 90_092L, " ", true, false, UserStatus.ACTIVE);
        User admin = authorizedUser(93L, 90_093L, null, false, true, UserStatus.ACTIVE);
        User engineeringUser = authorizedUser(94L, 90_094L, "Engineering", false, false, UserStatus.INACTIVE);
        User financeUser = authorizedUser(95L, 90_095L, "Finance", false, false, UserStatus.ACTIVE);
        DailyReport engineeringReport = report(901L, engineeringUser, "engineering");
        DailyReport financeReport = report(902L, financeUser, "finance");
        for (User actor : List.of(manager, inactiveManager, managerWithoutDepartment, admin)) {
            when(userRepository.findByTelegramUserId(actor.getTelegramUserId())).thenReturn(Optional.of(actor));
        }

        assertThat(service.readDepartmentReports(
                manager.getTelegramUserId(), "Sales", reportDate
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.readDepartmentReports(
                inactiveManager.getTelegramUserId(), "Engineering", reportDate
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        assertThat(service.readDepartmentReports(
                managerWithoutDepartment.getTelegramUserId(), "Engineering", reportDate
        ).status()).isEqualTo(IdentityAdminStatus.ACCESS_DENIED);
        verify(userRepository, never()).findCurrentTeamMembers("Sales", null);
        verify(userRepository, never()).findCurrentTeamMembers("Engineering", null);
        verifyNoInteractions(dailyReportRepository, auditRepository);

        when(userRepository.findCurrentTeamMembers("eNgInEeRiNg", null))
                .thenReturn(List.of(engineeringUser));
        when(userRepository.findCurrentTeamMembers("Finance", null)).thenReturn(List.of(financeUser));
        when(dailyReportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
                List.of(engineeringUser.getId()), reportDate
        )).thenReturn(List.of(engineeringReport));
        when(dailyReportRepository.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(
                List.of(financeUser.getId()), reportDate
        )).thenReturn(List.of(financeReport));

        ManagementReadResult managerRead = service.readDepartmentReports(
                manager.getTelegramUserId(), " eNgInEeRiNg ", reportDate
        );
        ManagementReadResult adminRead = service.readDepartmentReports(
                admin.getTelegramUserId(), "Finance", reportDate
        );

        assertThat(managerRead.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(managerRead.users()).containsExactly(engineeringUser);
        assertThat(managerRead.reports()).containsExactly(engineeringReport);
        assertThat(adminRead.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(adminRead.users()).containsExactly(financeUser);
        assertThat(adminRead.reports()).containsExactly(financeReport);
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(dailyReportRepository, never()).saveAndFlush(any(DailyReport.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldTreatNormalizedDepartmentTransferAsNoChange() {
        User admin = authorizedUser(100L, 100_100L, null, false, true, UserStatus.ACTIVE);
        User target = authorizedUser(101L, 100_101L, "Engineering", true, true, UserStatus.INACTIVE);
        target.setUnitName("Core");
        when(userRepository.findByTelegramUserId(admin.getTelegramUserId())).thenReturn(Optional.of(admin));
        when(userRepository.findByTelegramUserId(target.getTelegramUserId())).thenReturn(Optional.of(target));

        IdentityAdminResult result = service.changeDepartment(
                admin.getTelegramUserId(),
                target.getTelegramUserId(),
                " Engineering ",
                " Core ",
                "same assignment"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.NO_CHANGE);
        assertThat(result.user()).isSameAs(target);
        assertThat(target.getDepartmentName()).isEqualTo("Engineering");
        assertThat(target.getUnitName()).isEqualTo("Core");
        verify(userRepository, never()).saveAndFlush(any(User.class));
        verifyNoInteractions(dailyReportRepository, auditRepository);
    }

    private IdentityAdminCommand command(String employeeCode, String department, String reason) {
        return new IdentityAdminCommand(
                null,
                null,
                null,
                null,
                "+84900000001",
                employeeCode,
                "Nguyen Van A",
                department,
                "Delivery",
                UserStatus.ACTIVE,
                "internal-ops",
                reason
        );
    }

    private void stubMappedUser(User user) {
        when(userRepository.findByTelegramUserId(user.getTelegramUserId())).thenReturn(Optional.of(user));
    }

    private User managedUser() {
        User user = new User();
        user.setId(7L);
        user.setPhoneNumber("+84900000001");
        user.setEmployeeCode("EMP-01");
        user.setFullName("Nguyen Van A");
        user.setDepartmentName("Engineering");
        user.setUnitName("Delivery");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private User routingUser(Long id, Long telegramUserId) {
        User user = managedUser();
        user.setId(id);
        user.setTelegramUserId(telegramUserId);
        if (telegramUserId != null) {
            user.setChatId(1001L);
            user.setUsername("telegram-user");
            user.setFirstName("An");
        }
        return user;
    }

    private User authorizedUser(
            long id,
            long telegramUserId,
            String departmentName,
            boolean manager,
            boolean admin,
            UserStatus status
    ) {
        User user = new User();
        user.setId(id);
        user.setTelegramUserId(telegramUserId);
        user.setChatId(telegramUserId + 10_000L);
        user.setUsername("telegram-user-" + id);
        user.setFirstName("User " + id);
        user.setPhoneNumber("+8490000000" + id);
        user.setEmployeeCode("EMP-" + id);
        user.setFullName("Employee " + id);
        user.setDepartmentName(departmentName);
        user.setUnitName("Unit " + id);
        user.setManager(manager);
        user.setAdmin(admin);
        user.setStatus(status);
        return user;
    }

    private DailyReport report(long id, User owner, String content) {
        DailyReport report = new DailyReport();
        report.setId(id);
        report.setUser(owner);
        report.setReportDate(LocalDate.of(2026, 7, 1));
        report.setContent(content);
        report.setCollaborators("Existing collaborators");
        report.setDepartment(owner.getDepartmentName());
        report.setUnit(owner.getUnitName());
        report.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 30));
        return report;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
