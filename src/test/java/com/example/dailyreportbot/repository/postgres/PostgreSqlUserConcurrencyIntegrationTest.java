package com.example.dailyreportbot.repository.postgres;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.IdentityAuditEventRepository;
import com.example.dailyreportbot.repository.UserRepository;
import com.example.dailyreportbot.service.IdentityAdminCommand;
import com.example.dailyreportbot.service.IdentityAdminResult;
import com.example.dailyreportbot.service.IdentityAdminStatus;
import com.example.dailyreportbot.service.IdentityAdministrationService;
import com.example.dailyreportbot.service.DailyReportService;
import com.example.dailyreportbot.service.DailyReportSubmissionStatus;
import com.example.dailyreportbot.service.UserRegistrationService;
import com.example.dailyreportbot.service.UserRegistrationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgreSqlUserConcurrencyIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private IdentityAdministrationService administrationService;

    @Autowired
    private DailyReportService dailyReportService;

    @Autowired
    private UserRegistrationService registrationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityAuditEventRepository auditRepository;

    @Autowired
    private DailyReportRepository reportRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentProfileAndLifecycleMutationsHaveOneWinnerAndOneAudit() throws Exception {
        long userId = createManagedUser(1001L, "EMP-01");
        Future<IdentityAdminResult> profile;
        Future<IdentityAdminResult> lifecycle;
        try (BlockedUserUpdate ignored = blockUserUpdates(userId)) {
            profile = executor.submit(() -> administrationService.updateUser(
                    userId,
                    command("+841001", "EMP-02", "Platform", UserStatus.ACTIVE, "profile race")
            ));
            lifecycle = executor.submit(() -> administrationService.changeStatus(
                    userId,
                    UserStatus.INACTIVE,
                    "internal-ops",
                    "lifecycle race"
            ));
            awaitBlockedUpdates(2);
        }

        List<IdentityAdminStatus> statuses = List.of(
                profile.get().status(),
                lifecycle.get().status()
        );
        assertThat(statuses).contains(IdentityAdminStatus.IDENTITY_CONFLICT);
        assertThat(statuses.stream().filter(this::isGovernedSuccess).count()).isEqualTo(1);
        assertThat(auditRepository.findHistory(userId, 10)).hasSize(1);
        assertThat(userRepository.findByInternalId(userId).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    void concurrentRemapAndTargetProfileUpdateRollBackLosingRemapWithoutMovingReports() throws Exception {
        long sourceId = createManagedUser(2001L, "EMP-11");
        long targetId = createManagedUser(null, "EMP-12");
        long reportId = createReport(sourceId);
        Future<IdentityAdminResult> remap;
        Future<IdentityAdminResult> profile;
        try (BlockedUserUpdate ignored = blockUserUpdates(targetId)) {
            remap = executor.submit(() -> administrationService.remapTelegram(
                    sourceId,
                    targetId,
                    "internal-ops",
                    "remap race"
            ));
            profile = executor.submit(() -> administrationService.updateUser(
                    targetId,
                    command("+8412", "EMP-12", "Platform", UserStatus.ACTIVE, "target profile race")
            ));
            awaitBlockedUpdates(2);
        }

        List<IdentityAdminStatus> statuses = List.of(remap.get().status(), profile.get().status());
        assertThat(statuses).contains(IdentityAdminStatus.IDENTITY_CONFLICT);
        assertThat(statuses.stream().filter(this::isGovernedSuccess).count()).isEqualTo(1);
        assertThat(auditRepository.findHistory(targetId, 10)).hasSize(1);
        assertThat(reportRepository.findById(reportId).orElseThrow().getUser().getId()).isEqualTo(sourceId);
    }

    @Test
    void concurrentRegistrationRefreshAndLifecycleChangeHaveDeterministicWinner() throws Exception {
        long userId = createManagedUser(3001L, "EMP-21");
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                new org.telegram.telegrambots.meta.api.objects.User();
        telegramUser.setId(3001L);
        telegramUser.setFirstName("Refreshed");
        telegramUser.setIsBot(false);
        telegramUser.setUserName("refreshed_user");
        Future<UserRegistrationStatus> refresh;
        Future<IdentityAdminResult> lifecycle;
        try (BlockedUserUpdate ignored = blockUserUpdates(userId)) {
            refresh = executor.submit(() -> registrationService.registerOrUpdate(telegramUser, 7777L));
            lifecycle = executor.submit(() -> administrationService.changeStatus(
                    userId,
                    UserStatus.INACTIVE,
                    "internal-ops",
                    "registration lifecycle race"
            ));
            awaitBlockedUpdates(2);
        }

        UserRegistrationStatus refreshStatus = refresh.get();
        IdentityAdminStatus lifecycleStatus = lifecycle.get().status();
        assertThat(
                refreshStatus == UserRegistrationStatus.REFRESHED
                        && lifecycleStatus == IdentityAdminStatus.IDENTITY_CONFLICT
                        || refreshStatus == UserRegistrationStatus.CONFLICT
                        && lifecycleStatus == IdentityAdminStatus.DEACTIVATED
        ).isTrue();
        int expectedAuditCount = lifecycleStatus == IdentityAdminStatus.DEACTIVATED ? 1 : 0;
        assertThat(auditRepository.findHistory(userId, 10)).hasSize(expectedAuditCount);
        assertThat(userRepository.findByInternalId(userId).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    void reportConfirmationAndRemapEitherSaveOldOwnerOrRejectStaleIdentity() throws Exception {
        long telegramUserId = 4001L;
        long sourceId = createManagedUser(telegramUserId, "EMP-31");
        long targetId = createManagedUser(null, "EMP-32");
        String content = "report-remap-race-" + System.nanoTime();
        Future<DailyReportSubmissionStatus> submission;
        Future<IdentityAdminResult> remap;

        try (BlockedUserUpdate ignored = blockUserUpdates(sourceId)) {
            submission = executor.submit(() -> dailyReportService.submitToday(
                    telegramUserId,
                    sourceId,
                    content,
                    "Không có",
                    Instant.parse("2026-07-16T05:00:00Z")
            ));
            remap = executor.submit(() -> administrationService.remapTelegram(
                    sourceId,
                    targetId,
                    "internal-ops",
                    "report confirmation race"
            ));
            awaitBlockedUserLocks(2);
        }

        DailyReportSubmissionStatus submissionStatus = submission.get();
        assertThat(remap.get().status()).isEqualTo(IdentityAdminStatus.REMAPPED);
        assertThat(submissionStatus).isIn(
                DailyReportSubmissionStatus.SAVED,
                DailyReportSubmissionStatus.IDENTITY_CHANGED
        );

        List<DailyReport> matchingReports = reportRepository.findAll().stream()
                .filter(report -> content.equals(report.getContent()))
                .toList();
        if (submissionStatus == DailyReportSubmissionStatus.SAVED) {
            assertThat(matchingReports).singleElement()
                    .extracting(report -> report.getUser().getId())
                    .isEqualTo(sourceId);
        } else {
            assertThat(matchingReports).isEmpty();
        }
        assertThat(userRepository.findByInternalId(sourceId).orElseThrow().getTelegramUserId()).isNull();
        assertThat(userRepository.findByInternalId(targetId).orElseThrow().getTelegramUserId())
                .isEqualTo(telegramUserId);
    }

    private BlockedUserUpdate blockUserUpdates(long userId) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? FOR UPDATE"
        )) {
            statement.setLong(1, userId);
            statement.executeQuery().close();
        }
        return new BlockedUserUpdate(connection);
    }

    private void awaitBlockedUpdates(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + CONCURRENCY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND lower(query) LIKE 'update users%'
                    """,
                    Integer.class
            );
            if (blocked != null && blocked >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + expected + " blocked user updates");
    }

    private void awaitBlockedUserLocks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + CONCURRENCY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND state = 'active'
                      AND wait_event_type = 'Lock'
                      AND lower(query) LIKE '%users%'
                    """,
                    Integer.class
            );
            if (blocked != null && blocked >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + expected + " blocked user locks");
    }

    private long createManagedUser(Long telegramUserId, String employeeCode) {
        return inNewTransaction(transactionManager, () -> {
            User user = new User();
            user.setPhoneNumber("+84" + (telegramUserId == null
                    ? employeeCode.replaceAll("\\D", "")
                    : telegramUserId));
            user.setTelegramUserId(telegramUserId);
            user.setChatId(telegramUserId == null ? null : 9000L + telegramUserId);
            user.setEmployeeCode(employeeCode);
            user.setFullName("Concurrent User");
            user.setDepartmentName("Engineering");
            user.setUnitName("Delivery");
            return userRepository.saveAndFlush(user).getId();
        });
    }

    private long createReport(long ownerId) {
        return inNewTransaction(transactionManager, () -> {
            DailyReport report = new DailyReport();
            report.setUser(userRepository.findByInternalId(ownerId).orElseThrow());
            report.setReportDate(LocalDate.of(2026, 7, 8));
            report.setContent("ownership evidence");
            return reportRepository.saveAndFlush(report).getId();
        });
    }

    private IdentityAdminCommand command(
            String phoneNumber,
            String employeeCode,
            String department,
            UserStatus status,
            String reason
    ) {
        return new IdentityAdminCommand(
                null, null, null, null, phoneNumber,
                employeeCode,
                "Concurrent User",
                department,
                "Delivery",
                status,
                "internal-ops",
                reason
        );
    }

    private boolean isGovernedSuccess(IdentityAdminStatus status) {
        return status == IdentityAdminStatus.UPDATED
                || status == IdentityAdminStatus.DEACTIVATED
                || status == IdentityAdminStatus.REACTIVATED
                || status == IdentityAdminStatus.REMAPPED;
    }

    private static final class BlockedUserUpdate implements AutoCloseable {
        private final Connection connection;

        private BlockedUserUpdate(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.commit();
            } finally {
                connection.close();
            }
        }
    }
}
