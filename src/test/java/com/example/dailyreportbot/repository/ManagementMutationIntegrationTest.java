package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.IdentityAdminAction;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.service.IdentityAdminResult;
import com.example.dailyreportbot.service.IdentityAdminStatus;
import com.example.dailyreportbot.service.IdentityAdministrationService;
import com.example.dailyreportbot.service.ManagementReadResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:management-mutation-integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        IdentityAdministrationService.class,
        IdentityAuditEventRepository.class,
        ManagementMutationIntegrationTest.ClockConfiguration.class
})
@Transactional
class ManagementMutationIntegrationTest {

    private static final AtomicLong TELEGRAM_ID = new AtomicLong(90_000);
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 7, 2);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 2, 9, 30);

    @Autowired
    private IdentityAdministrationService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DailyReportRepository reportRepository;

    @SpyBean
    private IdentityAuditEventRepository auditRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void editsOnlyReportContentAndAppendsPrivacySafeAudit() {
        User actor = saveUser(true, "Administration", "Operations");
        User owner = saveUser(false, "Engineering", "Platform");
        DailyReport report = saveReport(owner, "private body before edit");
        report.setPerformer("Existing performer");
        report.setCollaborators("Existing collaborator");
        reportRepository.saveAndFlush(report);
        Long reportId = report.getId();
        Long ownerId = owner.getId();

        IdentityAdminResult result = service.editReport(
                actor.getTelegramUserId(),
                reportId,
                "private body after edit",
                "correct submitted report"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        entityManager.clear();
        DailyReport stored = reportRepository.findById(reportId).orElseThrow();
        assertThat(stored.getContent()).isEqualTo("private body after edit");
        assertThat(stored.getUser().getId()).isEqualTo(ownerId);
        assertThat(stored.getReportDate()).isEqualTo(REPORT_DATE);
        assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(stored.getDepartment()).isEqualTo("Submitted Engineering");
        assertThat(stored.getUnit()).isEqualTo("Submitted Platform");
        assertThat(stored.getPerformer()).isEqualTo("Existing performer");
        assertThat(stored.getCollaborators()).isEqualTo("Existing collaborator");

        List<IdentityAuditEvent> history = auditRepository.findHistory(ownerId, 10);
        assertThat(history).hasSize(1);
        IdentityAuditEvent event = history.get(0);
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.UPDATE_REPORT);
        assertThat(event.getBeforeState())
                .doesNotContain("private body before edit", "private body after edit");
        assertThat(event.getAfterState())
                .doesNotContain("private body before edit", "private body after edit");
    }

    @Test
    void hardDeletesReportAndAppendsPrivacySafeAudit() {
        User actor = saveUser(true, "Administration", "Operations");
        User owner = saveUser(false, "Finance", "Accounting");
        DailyReport report = saveReport(owner, "private body to delete");
        Long reportId = report.getId();
        Long ownerId = owner.getId();

        IdentityAdminResult result = service.deleteReport(
                actor.getTelegramUserId(),
                reportId,
                "duplicate submission"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.DELETED);
        entityManager.clear();
        assertThat(reportRepository.findById(reportId)).isEmpty();
        List<IdentityAuditEvent> history = auditRepository.findHistory(ownerId, 10);
        assertThat(history).hasSize(1);
        IdentityAuditEvent event = history.get(0);
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.DELETE_REPORT);
        assertThat(event.getBeforeState()).doesNotContain("private body to delete");
        assertThat(event.getAfterState()).doesNotContain("private body to delete");
    }

    @Test
    void rollsBackReportEditAndDeleteWhenAuditAppendFails() {
        User actor = saveUser(true, "Administration", "Operations");
        User owner = saveUser(false, "Engineering", "Delivery");
        DailyReport editTarget = saveReport(owner, "original edit body");
        DailyReport deleteTarget = saveReport(owner, "original delete body");
        Long actorTelegramId = actor.getTelegramUserId();
        Long ownerId = owner.getId();
        Long editTargetId = editTarget.getId();
        Long deleteTargetId = deleteTarget.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(auditRepository).append(any(IdentityAuditEvent.class));

        IdentityAdminResult editResult = service.editReport(
                actorTelegramId,
                editTargetId,
                "changed edit body",
                "edit reason"
        );
        assertThat(editResult.status()).isEqualTo(IdentityAdminStatus.FAILED);
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        TestTransaction.end();
        TestTransaction.start();
        assertThat(reportRepository.findById(editTargetId).orElseThrow().getContent())
                .isEqualTo("original edit body");

        IdentityAdminResult deleteResult = service.deleteReport(
                actorTelegramId,
                deleteTargetId,
                "delete reason"
        );
        assertThat(deleteResult.status()).isEqualTo(IdentityAdminStatus.FAILED);
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        TestTransaction.end();
        TestTransaction.start();
        assertThat(reportRepository.findById(deleteTargetId).orElseThrow().getContent())
                .isEqualTo("original delete body");
        assertThat(auditRepository.findHistory(ownerId, 10)).isEmpty();
    }

    @Test
    void grantsIndependentRoleToInactiveTargetAndAppendsAudit() {
        User actor = saveUser(true, "Administration", "Operations");
        User target = saveUser(false, "Engineering", "Platform");
        target.setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(target);
        Long targetId = target.getId();
        Long targetTelegramId = target.getTelegramUserId();

        IdentityAdminResult result = service.changeRole(
                actor.getTelegramUserId(),
                targetTelegramId,
                IdentityAdministrationService.Role.MANAGER,
                IdentityAdministrationService.RoleChange.GRANT,
                "prepare next manager"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        entityManager.clear();
        User stored = userRepository.findByTelegramUserId(targetTelegramId).orElseThrow();
        assertThat(stored.isManager()).isTrue();
        assertThat(stored.isAdmin()).isFalse();
        assertThat(stored.getStatus()).isEqualTo(UserStatus.INACTIVE);
        List<IdentityAuditEvent> history = auditRepository.findHistory(targetId, 10);
        assertThat(history).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo(IdentityAdminAction.GRANT_MANAGER);
            assertThat(event.getBeforeState()).contains("manager=false", "admin=false");
            assertThat(event.getAfterState()).contains("manager=true", "admin=false");
        });
    }

    @Test
    void deactivatesTargetWithoutRemovingPersistedRoles() {
        User actor = saveUser(true, "Administration", "Operations");
        User target = saveUser(false, "Engineering", "Platform");
        target.setManager(true);
        target.setAdmin(true);
        userRepository.saveAndFlush(target);
        Long targetId = target.getId();
        Long targetTelegramId = target.getTelegramUserId();

        IdentityAdminResult result = service.changeStatus(
                actor.getTelegramUserId(), targetTelegramId, UserStatus.INACTIVE, "leave of absence"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.DEACTIVATED);
        entityManager.clear();
        User stored = userRepository.findByTelegramUserId(targetTelegramId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(stored.isManager()).isTrue();
        assertThat(stored.isAdmin()).isTrue();
        assertThat(auditRepository.findHistory(targetId, 10)).singleElement().satisfies(event ->
                assertThat(event.getAction()).isEqualTo(IdentityAdminAction.DEACTIVATE)
        );
    }

    @Test
    void rollsBackRoleChangeWhenAuditAppendFails() {
        User actor = saveUser(true, "Administration", "Operations");
        User target = saveUser(false, "Engineering", "Platform");
        Long actorTelegramId = actor.getTelegramUserId();
        Long targetTelegramId = target.getTelegramUserId();
        Long targetId = target.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(auditRepository).append(any(IdentityAuditEvent.class));

        IdentityAdminResult result = service.changeRole(
                actorTelegramId,
                targetTelegramId,
                IdentityAdministrationService.Role.ADMIN,
                IdentityAdministrationService.RoleChange.GRANT,
                "grant administration"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.FAILED);
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        TestTransaction.end();
        TestTransaction.start();
        User stored = userRepository.findByTelegramUserId(targetTelegramId).orElseThrow();
        assertThat(stored.isAdmin()).isFalse();
        assertThat(auditRepository.findHistory(targetId, 10)).isEmpty();
    }

    @Test
    void transfersDepartmentWithoutChangingHistoricalReportOrRoles() {
        User actor = saveUser(true, "Administration", "Operations");
        User target = saveUser(false, "Engineering", "Platform");
        target.setManager(true);
        target.setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(target);
        DailyReport report = saveReport(target, "historical body");
        report.setCollaborators("Historical collaborator");
        reportRepository.saveAndFlush(report);
        Long targetId = target.getId();
        Long reportId = report.getId();

        IdentityAdminResult result = service.changeDepartment(
                actor.getTelegramUserId(),
                target.getTelegramUserId(),
                "Finance",
                "Accounting",
                "organization transfer"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.UPDATED);
        entityManager.clear();
        User storedUser = userRepository.findByTelegramUserId(target.getTelegramUserId()).orElseThrow();
        assertThat(storedUser.getDepartmentName()).isEqualTo("Finance");
        assertThat(storedUser.getUnitName()).isEqualTo("Accounting");
        assertThat(storedUser.isManager()).isTrue();
        assertThat(storedUser.isAdmin()).isFalse();
        assertThat(storedUser.getStatus()).isEqualTo(UserStatus.INACTIVE);
        DailyReport storedReport = reportRepository.findById(reportId).orElseThrow();
        assertThat(storedReport.getUser().getId()).isEqualTo(targetId);
        assertThat(storedReport.getReportDate()).isEqualTo(REPORT_DATE);
        assertThat(storedReport.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(storedReport.getDepartment()).isEqualTo("Submitted Engineering");
        assertThat(storedReport.getUnit()).isEqualTo("Submitted Platform");
        assertThat(storedReport.getCollaborators()).isEqualTo("Historical collaborator");
        assertThat(auditRepository.findHistory(targetId, 10)).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo(IdentityAdminAction.UPDATE_PROFILE);
            assertThat(event.getBeforeState()).contains("departmentName=Engineering");
            assertThat(event.getAfterState()).contains("departmentName=Finance");
        });
    }

    @Test
    void rollsBackDepartmentTransferWhenAuditAppendFails() {
        User actor = saveUser(true, "Administration", "Operations");
        User target = saveUser(false, "Engineering", "Platform");
        Long actorTelegramId = actor.getTelegramUserId();
        Long targetTelegramId = target.getTelegramUserId();
        Long targetId = target.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(auditRepository).append(any(IdentityAuditEvent.class));

        IdentityAdminResult result = service.changeDepartment(
                actorTelegramId,
                targetTelegramId,
                "Finance",
                null,
                "organization transfer"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.FAILED);
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        TestTransaction.end();
        TestTransaction.start();
        User stored = userRepository.findByTelegramUserId(targetTelegramId).orElseThrow();
        assertThat(stored.getDepartmentName()).isEqualTo("Engineering");
        assertThat(stored.getUnitName()).isEqualTo("Platform");
        assertThat(auditRepository.findHistory(targetId, 10)).isEmpty();
    }

    @Test
    void managementReadsWriteNoBusinessOrAuditRows() {
        User actor = saveUser(true, "Administration", "Operations");
        User target = saveUser(false, "Read Scope", "Platform");
        DailyReport report = saveReport(target, "authorized body");
        long userCount = userRepository.count();
        long reportCount = reportRepository.count();
        long actorVersion = actor.getVersion();
        long targetVersion = target.getVersion();

        assertThat(service.readUsers(actor.getTelegramUserId()).users()).contains(actor, target);
        assertThat(service.readUser(actor.getTelegramUserId(), target.getTelegramUserId()).users())
                .containsExactly(target);
        assertThat(service.readReports(
                actor.getTelegramUserId(), target.getTelegramUserId(), REPORT_DATE
        ).reports()).extracting(DailyReport::getId).containsExactly(report.getId());
        assertThat(service.readRecentReports(
                actor.getTelegramUserId(), target.getTelegramUserId()
        ).reports()).extracting(DailyReport::getId).containsExactly(report.getId());
        assertThat(service.readReport(actor.getTelegramUserId(), report.getId()).reports())
                .extracting(DailyReport::getId).containsExactly(report.getId());
        assertThat(service.readDepartmentReports(
                actor.getTelegramUserId(), "Read Scope", REPORT_DATE
        ).users()).containsExactly(target);
        assertThat(service.readOrganizationReports(
                actor.getTelegramUserId(), REPORT_DATE
        ).users()).contains(actor, target);
        assertThat(service.readAudit(
                actor.getTelegramUserId(), target.getTelegramUserId()
        ).status()).isEqualTo(IdentityAdminStatus.NO_DATA);
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.count()).isEqualTo(userCount);
        assertThat(reportRepository.count()).isEqualTo(reportCount);
        User storedActor = userRepository.findByTelegramUserId(actor.getTelegramUserId()).orElseThrow();
        User storedTarget = userRepository.findByTelegramUserId(target.getTelegramUserId()).orElseThrow();
        assertThat(storedActor.getVersion()).isEqualTo(actorVersion);
        assertThat(storedTarget.getVersion()).isEqualTo(targetVersion);
        assertThat(storedTarget.getDepartmentName()).isEqualTo("Read Scope");
        assertThat(storedTarget.getUnitName()).isEqualTo("Platform");
        assertThat(reportRepository.findById(report.getId()).orElseThrow().getContent())
                .isEqualTo("authorized body");
        assertThat(entityManager.createQuery(
                "SELECT COUNT(event) FROM IdentityAuditEvent event", Long.class
        ).getSingleResult()).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void returnsInitializedReportOwnerAfterReadTransactionCloses() {
        User actor = saveUser(true, "Administration", "Operations");
        User owner = saveUser(false, "Detached Scope", "Platform");
        DailyReport report = saveReport(owner, "detached read body");
        Long actorTelegramId = actor.getTelegramUserId();
        Long reportId = report.getId();
        String ownerName = owner.getFullName();

        ManagementReadResult result = service.readReport(actorTelegramId, reportId);

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.SUCCESS);
        assertThat(result.users().get(0).getFullName()).isEqualTo(ownerName);
    }

    private User saveUser(boolean admin, String department, String unit) {
        long telegramId = TELEGRAM_ID.incrementAndGet();
        User user = new User();
        user.setPhoneNumber("+84" + telegramId);
        user.setTelegramUserId(telegramId);
        user.setFullName("User " + telegramId);
        user.setDepartmentName(department);
        user.setUnitName(unit);
        user.setAdmin(admin);
        return userRepository.saveAndFlush(user);
    }

    private DailyReport saveReport(User owner, String content) {
        DailyReport report = new DailyReport();
        report.setUser(owner);
        report.setReportDate(REPORT_DATE);
        report.setContent(content);
        report.setDepartment("Submitted Engineering");
        report.setUnit("Submitted Platform");
        report.setCreatedAt(CREATED_AT);
        return reportRepository.saveAndFlush(report);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        Clock testClock() {
            return Clock.fixed(CREATED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }
    }
}
