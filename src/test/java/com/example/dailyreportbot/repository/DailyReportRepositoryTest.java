package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
class DailyReportRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DailyReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExposeFlywayUserDateIndex() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = ?",
                Integer.class,
                "IDX_DAILY_REPORTS_USER_DATE_CREATED"
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldFindReportsThroughSingleUserRelationshipNewestFirst() {
        User user = new User();
        user.setPhoneNumber("+8412345");
        user.setTelegramUserId(12345L);
        user.setEmployeeCode("EMP001");
        user = entityManager.persist(user);
        User otherUser = new User();
        otherUser.setPhoneNumber("+8467890");
        otherUser.setTelegramUserId(67890L);
        otherUser = entityManager.persist(otherUser);

        LocalDate date = LocalDate.of(2026, 7, 1);
        DailyReport older = persistReport(user, date, LocalDateTime.of(2026, 7, 1, 9, 0), "Older");
        DailyReport lowerIdAtLatestTime = persistReport(
                user,
                date,
                LocalDateTime.of(2026, 7, 1, 18, 0),
                "Latest time, lower ID"
        );
        DailyReport higherIdAtLatestTime = persistReport(
                user,
                date,
                LocalDateTime.of(2026, 7, 1, 18, 0),
                "Latest time, higher ID"
        );
        persistReport(otherUser, date, LocalDateTime.of(2026, 7, 1, 20, 0), "Other user");
        entityManager.flush();
        entityManager.clear();

        List<DailyReport> reports = repository
                .findByUser_TelegramUserIdAndReportDateOrderByCreatedAtDescIdDesc(12345L, date);

        assertThat(reports).extracting(DailyReport::getId).containsExactly(
                higherIdAtLatestTime.getId(),
                lowerIdAtLatestTime.getId(),
                older.getId()
        );
        assertThat(reports.get(0).getUser().getEmployeeCode()).isEqualTo("EMP001");

        assertThat(repository.findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(user.getId(), date))
                .extracting(DailyReport::getId)
                .containsExactly(
                        higherIdAtLatestTime.getId(),
                        lowerIdAtLatestTime.getId(),
                        older.getId()
                );

        List<DailyReport> latestOnly = repository
                .findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(12345L, PageRequest.of(0, 1));
        assertThat(latestOnly).extracting(DailyReport::getId).containsExactly(higherIdAtLatestTime.getId());
    }

    @Test
    void shouldRoundTripEveryAdminManagerCombination() {
        List<User> users = List.of(
                roleUser("+8410001", false, false),
                roleUser("+8410002", false, true),
                roleUser("+8410003", true, false),
                roleUser("+8410004", true, true)
        );
        users.forEach(entityManager::persist);
        entityManager.flush();
        entityManager.clear();

        List<User> reloaded = users.stream()
                .map(user -> entityManager.find(User.class, user.getPhoneNumber()))
                .toList();

        assertThat(reloaded)
                .extracting(User::isAdmin, User::isManager)
                .containsExactly(
                        tuple(false, false),
                        tuple(false, true),
                        tuple(true, false),
                        tuple(true, true)
                );
    }

    @Test
    void shouldRoundTripReportOrganizationSnapshotsIncludingNulls() {
        User user = new User();
        user.setPhoneNumber("+8419999");
        user = entityManager.persist(user);
        LocalDate date = LocalDate.of(2026, 7, 16);
        DailyReport labeled = persistReport(
                user,
                date,
                LocalDateTime.of(2026, 7, 16, 9, 0),
                "Labeled snapshot"
        );
        labeled.setDepartment("Engineering");
        labeled.setUnit("Platform");
        DailyReport unlabeled = persistReport(
                user,
                date,
                LocalDateTime.of(2026, 7, 16, 10, 0),
                "Null snapshot"
        );
        entityManager.flush();
        entityManager.clear();

        List<DailyReport> reloaded = List.of(
                entityManager.find(DailyReport.class, labeled.getId()),
                entityManager.find(DailyReport.class, unlabeled.getId())
        );

        assertThat(reloaded)
                .extracting(DailyReport::getDepartment, DailyReport::getUnit)
                .containsExactly(tuple("Engineering", "Platform"), tuple(null, null));
    }

    private User roleUser(String phoneNumber, boolean admin, boolean manager) {
        User user = new User();
        user.setPhoneNumber(phoneNumber);
        user.setAdmin(admin);
        user.setManager(manager);
        return user;
    }

    private DailyReport persistReport(User user, LocalDate date, LocalDateTime createdAt, String content) {
        DailyReport report = new DailyReport();
        report.setUser(user);
        report.setReportDate(date);
        report.setCreatedAt(createdAt);
        report.setContent(content);
        return entityManager.persist(report);
    }
}
