package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.service.TeamReportPolicy;
import com.example.dailyreportbot.service.TeamReportReadService;
import com.example.dailyreportbot.service.TeamReportStatus;
import com.example.dailyreportbot.service.TeamReportSummary;
import com.example.dailyreportbot.service.TeamScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({TeamReportReadService.class, TeamReportPolicy.class})
class TeamReportReadRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DailyReportRepository reportRepository;

    @Autowired
    private TeamReportReadService readService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final LocalDate date = LocalDate.of(2026, 7, 3);

    @Test
    void shouldSelectTrimmedCaseInsensitiveDepartmentAndOptionalUnitCurrentRoster() {
        User platform = persistUser(null, "Platform Active", "  Engineering ", " Platform ", UserStatus.ACTIVE);
        User platformInactive = persistUser(null, "Platform Inactive", "engineering", "platform", UserStatus.INACTIVE);
        User qa = persistUser(303L, "QA", "ENGINEERING", "QA", UserStatus.ACTIVE);
        persistUser(404L, "Other", "Operations", "Platform", UserStatus.ACTIVE);
        entityManager.flush();
        entityManager.clear();

        List<User> department = userRepository.findCurrentTeamMembers("Engineering", null);
        List<User> unit = userRepository.findCurrentTeamMembers("engineering", "PLATFORM");

        assertThat(department).extracting(User::getId)
                .containsExactlyInAnyOrder(platform.getId(), platformInactive.getId(), qa.getId());
        assertThat(unit).extracting(User::getId)
                .containsExactlyInAnyOrder(platform.getId(), platformInactive.getId());
    }

    @Test
    void shouldBatchReportsNewestFirstWithStableIdTieBreaking() {
        User user = persistUser(101L, "Owner", "Engineering", null, UserStatus.ACTIVE);
        LocalDateTime sameTime = date.atTime(9, 0);
        DailyReport first = persistReport(user, sameTime, "First");
        DailyReport second = persistReport(user, sameTime, "Second");
        entityManager.flush();
        entityManager.clear();

        List<DailyReport> reports = reportRepository
                .findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(List.of(user.getId()), date);

        assertThat(reports).extracting(DailyReport::getId).containsExactly(second.getId(), first.getId());
    }

    @Test
    void shouldReadCurrentRosterWithoutChangingRowsOrHistoricalOwnership() {
        User owner = persistUser(101L, "Owner", "Old Team", null, UserStatus.ACTIVE);
        DailyReport historical = persistReport(owner, date.atTime(16, 0), "Historical");
        entityManager.flush();

        owner.setDepartmentName("New Team");
        entityManager.flush();
        entityManager.clear();
        long usersBefore = count("users");
        long reportsBefore = count("daily_reports");
        long auditsBefore = count("identity_admin_audit_events");

        TeamReportSummary oldTeam = readService.readTeamSummary(
                new TeamScope("Old Team", null),
                date,
                Instant.parse("2026-07-03T11:00:00Z"),
                "Asia/Ho_Chi_Minh"
        );
        TeamReportSummary newTeam = readService.readTeamSummary(
                new TeamScope("New Team", null),
                date,
                Instant.parse("2026-07-03T11:00:00Z"),
                "Asia/Ho_Chi_Minh"
        );
        entityManager.flush();

        assertThat(oldTeam.members()).isEmpty();
        assertThat(newTeam.members()).hasSize(1);
        assertThat(newTeam.members().get(0).status()).isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
        assertThat(newTeam.members().get(0).reports()).extracting(evidence -> evidence.reportId())
                .containsExactly(historical.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM daily_reports WHERE id = ?",
                Long.class,
                historical.getId()
        )).isEqualTo(owner.getId());
        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("daily_reports")).isEqualTo(reportsBefore);
        assertThat(count("identity_admin_audit_events")).isEqualTo(auditsBefore);
    }

    private User persistUser(
            Long telegramUserId,
            String fullName,
            String department,
            String unit,
            UserStatus status
    ) {
        User user = new User();
        user.setPhoneNumber("+84" + Math.abs(fullName.hashCode()));
        user.setTelegramUserId(telegramUserId);
        user.setFullName(fullName);
        user.setDepartmentName(department);
        user.setUnitName(unit);
        user.setStatus(status);
        return entityManager.persist(user);
    }

    private DailyReport persistReport(User user, LocalDateTime createdAt, String content) {
        DailyReport report = new DailyReport();
        report.setUser(user);
        report.setReportDate(date);
        report.setCreatedAt(createdAt);
        report.setContent(content);
        return entityManager.persist(report);
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
