package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.IdentityAdminAction;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.service.IdentityAdminCommand;
import com.example.dailyreportbot.service.IdentityAdminResult;
import com.example.dailyreportbot.service.IdentityAdminStatus;
import com.example.dailyreportbot.service.IdentityAdministrationService;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-admin-integration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        IdentityAdministrationService.class,
        IdentityAuditEventRepository.class,
        IdentityAdministrationIntegrationTest.ClockConfiguration.class
})
@Transactional
class IdentityAdministrationIntegrationTest {

    @Autowired
    private IdentityAdministrationService service;

    @Autowired
    private UserRepository userRepository;

    @SpyBean
    private IdentityAuditEventRepository auditRepository;

    @Autowired
    private DailyReportRepository dailyReportRepository;

    @Test
    void shouldCommitOneNormalizedUserAndExactlyOneAuditEvent() {
        IdentityAdminResult result = service.createUser(command(" emp-01 ", "+84900000001"));

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.CREATED);
        assertThat(userRepository.findByInternalId(result.user().getId()).orElseThrow().getEmployeeCode())
                .isEqualTo("EMP-01");
        List<IdentityAuditEvent> history = auditRepository.findHistory(result.user().getId(), 10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo(IdentityAdminAction.CREATE);
    }

    @Test
    void shouldCompleteOneInternalCreateOperationWithinTwoSecondsAtMvpScale() {
        long startedAt = System.nanoTime();

        IdentityAdminResult result = service.createUser(command("EMP-PERF", "+84900000888"));

        long elapsedNanos = System.nanoTime() - startedAt;
        assertThat(result.status()).isEqualTo(IdentityAdminStatus.CREATED);
        assertThat(elapsedNanos).isLessThan(Duration.ofSeconds(2).toNanos());
    }

    @Test
    void shouldRejectNormalizedConflictWithoutSecondUserOrAudit() {
        IdentityAdminResult first = service.createUser(command(" emp-01 ", "+84900000001"));

        IdentityAdminResult conflict = service.createUser(command("EMP-01", "+84900000002"));

        assertThat(conflict.status()).isEqualTo(IdentityAdminStatus.IDENTITY_CONFLICT);
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(auditRepository.findHistory(first.user().getId(), 10)).hasSize(1);
    }

    @Test
    void shouldRollbackUserWhenRequiredAuditAppendFails() {
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(auditRepository).append(any(IdentityAuditEvent.class));

        IdentityAdminResult result = service.createUser(command("EMP-ROLLBACK", "+84900000999"));

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.FAILED);
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        TestTransaction.end();
        TestTransaction.start();
        assertThat(userRepository.findByEmployeeCode("EMP-ROLLBACK")).isEmpty();
    }

    @Test
    void shouldFindOnlyExactFullNameIgnoringCaseAndPreserveDuplicates() {
        User first = user("+84900000101", "EMP-101", "Nguyen Van An");
        User duplicate = user("+84900000102", "EMP-102", "NGUYEN VAN AN");
        User partial = user("+84900000103", "EMP-103", "Nguyen Van Anh");
        userRepository.saveAllAndFlush(List.of(first, duplicate, partial));

        assertThat(userRepository.findAllByFullNameIgnoreCase("nguyen van an"))
                .extracting(User::getEmployeeCode)
                .containsExactlyInAnyOrder("EMP-101", "EMP-102");
    }

    @Test
    void shouldRemapCurrentTelegramRoutingWithoutReassigningHistoricalReports() {
        User source = new User();
        source.setPhoneNumber("+84900000010");
        source.setTelegramUserId(12345L);
        source.setChatId(1001L);
        source.setUsername("source-user");
        source.setFirstName("An");
        source.setEmployeeCode("EMP-01");
        source = userRepository.saveAndFlush(source);
        User target = new User();
        target.setPhoneNumber("+84900000011");
        target.setEmployeeCode("EMP-02");
        target = userRepository.saveAndFlush(target);
        DailyReport report = new DailyReport();
        report.setUser(source);
        report.setReportDate(LocalDate.of(2026, 7, 2));
        report.setContent("historical ownership evidence");
        report = dailyReportRepository.saveAndFlush(report);
        Long reportId = report.getId();
        Long sourceId = source.getId();
        Long targetId = target.getId();

        IdentityAdminResult result = service.remapTelegram(
                sourceId,
                targetId,
                "internal-ops",
                "correct mapping"
        );

        assertThat(result.status()).isEqualTo(IdentityAdminStatus.REMAPPED);
        assertThat(userRepository.findByInternalId(sourceId).orElseThrow().getTelegramUserId()).isNull();
        assertThat(userRepository.findByInternalId(targetId).orElseThrow().getTelegramUserId()).isEqualTo(12345L);
        assertThat(dailyReportRepository.findById(reportId).orElseThrow().getUser().getId()).isEqualTo(sourceId);
        IdentityAuditEvent event = auditRepository.findHistory(targetId, 10).get(0);
        assertThat(event.getAction()).isEqualTo(IdentityAdminAction.REMAP_TELEGRAM);
        assertThat(event.getRelatedUser().getId()).isEqualTo(sourceId);
        assertThat(auditRepository.findHistory(sourceId, 10))
                .extracting(IdentityAuditEvent::getAction)
                .containsExactly(IdentityAdminAction.REMAP_TELEGRAM);
    }

    private IdentityAdminCommand command(String employeeCode, String phoneNumber) {
        return new IdentityAdminCommand(
                null,
                null,
                null,
                null,
                phoneNumber,
                employeeCode,
                "Nguyen Van A",
                "Engineering",
                "Delivery",
                UserStatus.ACTIVE,
                "internal-ops",
                "create managed user"
        );
    }

    private User user(String phoneNumber, String employeeCode, String fullName) {
        User user = new User();
        user.setPhoneNumber(phoneNumber);
        user.setEmployeeCode(employeeCode);
        user.setFullName(fullName);
        return user;
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        Clock testClock() {
            return Clock.systemUTC();
        }
    }
}
