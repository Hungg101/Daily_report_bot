package com.example.dailyreportbot.repository.postgres;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.UserRepository;
import com.example.dailyreportbot.service.TeamMemberReportStatus;
import com.example.dailyreportbot.service.TeamReportEvidence;
import com.example.dailyreportbot.service.TeamReportPolicy;
import com.example.dailyreportbot.service.TeamReportReadService;
import com.example.dailyreportbot.service.TeamReportStatus;
import com.example.dailyreportbot.service.TeamReportSummary;
import com.example.dailyreportbot.service.TeamScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Import({TeamReportReadService.class, TeamReportPolicy.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlTeamReportScaleIntegrationTest extends PostgreSqlIntegrationTest {

    private static final int USER_COUNT = 1_000;
    private static final int REPORTS_PER_USER = 10;
    private static final int REPORT_COUNT = USER_COUNT * REPORTS_PER_USER;
    private static final int WARMUP_COUNT = 3;
    private static final int MEASURED_SAMPLE_COUNT = 20;
    private static final Duration TARGET_P95 = Duration.ofSeconds(2);
    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong(7_000);
    private static final Pattern EXECUTION_TIME = Pattern.compile(
            "(?m)^Execution Time:\\s+([0-9.]+) ms$"
    );
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 13);
    private static final Instant EVALUATED_AT = Instant.parse("2026-07-13T13:00:00Z");
    private static final String TIMEZONE_ID = "Asia/Ho_Chi_Minh";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DailyReportRepository reportRepository;

    @Autowired
    private TeamReportReadService readService;

    @Test
    void shouldMeasureOneThousandUsersAndTenThousandReportsThroughPostgreSql() {
        ScaleFixture fixture = seedFixture();
        assertFixtureCardinality(fixture);
        jdbc.execute("ANALYZE users");
        jdbc.execute("ANALYZE daily_reports");

        List<PlanEvidence> plans = List.of(
                explain(
                        "normalized_team_roster_scale",
                        """
                        SELECT user_account.id
                        FROM users user_account
                        WHERE lower(trim(user_account.department_name)) = lower(?)
                          AND (COALESCE(CAST(? AS VARCHAR), '') = ''
                               OR lower(trim(user_account.unit_name)) = lower(CAST(? AS VARCHAR)))
                        """,
                        fixture.scope().departmentName(),
                        null,
                        null
                ),
                explain(
                        "team_date_batch_reports_scale",
                        teamDateBatchSql(fixture.userIds().size()),
                        teamDateBatchArguments(fixture)
                )
        );
        plans.forEach(this::assertCompletePlan);

        List<User> persistedUsers = userRepository.findCurrentTeamMembers(
                fixture.scope().departmentName(),
                fixture.scope().unitName()
        );
        List<DailyReport> persistedReports = reportRepository
                .findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(fixture.userIds(), BUSINESS_DATE);
        assertThat(persistedUsers).hasSize(USER_COUNT);
        assertThat(persistedReports).hasSize(REPORT_COUNT);

        TeamReportReadService mappingService = mappingOnlyService(persistedUsers, persistedReports, fixture);
        List<Duration> javaMappingSamples = measureSamples(
                MEASURED_SAMPLE_COUNT,
                () -> mappingService.readTeamSummary(
                        fixture.scope(), BUSINESS_DATE, EVALUATED_AT, TIMEZONE_ID
                ),
                this::assertScaleSummary
        );

        for (int index = 0; index < WARMUP_COUNT; index++) {
            assertScaleSummary(readService.readTeamSummary(
                    fixture.scope(), BUSINESS_DATE, EVALUATED_AT, TIMEZONE_ID
            ));
        }
        List<Duration> endToEndSamples = measureSamples(
                MEASURED_SAMPLE_COUNT,
                () -> readService.readTeamSummary(
                        fixture.scope(), BUSINESS_DATE, EVALUATED_AT, TIMEZONE_ID
                ),
                this::assertScaleSummary
        );

        Duration javaMappingP95 = percentile95(javaMappingSamples);
        Duration endToEndP95 = percentile95(endToEndSamples);
        printEvidence(fixture, plans, javaMappingSamples, javaMappingP95, endToEndSamples, endToEndP95);

        assertThat(endToEndP95)
                .as("DBH-008 PostgreSQL-backed end-to-end p95 from %s measured samples", MEASURED_SAMPLE_COUNT)
                .isLessThan(TARGET_P95);
    }

    private ScaleFixture seedFixture() {
        long fixtureNumber = FIXTURE_SEQUENCE.incrementAndGet();
        long firstUserId = fixtureNumber * 1_000_000L;
        String department = "  DBH-008 Scale " + fixtureNumber + "  ";
        TeamScope scope = new TeamScope(department, null);
        LocalDateTime userTimestamp = BUSINESS_DATE.minusDays(1).atStartOfDay();
        List<Long> userIds = new ArrayList<>(USER_COUNT);
        List<Object[]> userArguments = new ArrayList<>(USER_COUNT);
        List<Object[]> reportArguments = new ArrayList<>(REPORT_COUNT);

        for (int userOffset = 0; userOffset < USER_COUNT; userOffset++) {
            long userId = firstUserId + userOffset;
            userIds.add(userId);
            userArguments.add(new Object[]{
                    userId,
                    "+84" + userId,
                    6_000_000_000L + userId,
                    "Scale " + String.format("%04d", userOffset + 1),
                    department,
                    Timestamp.valueOf(userTimestamp),
                    Timestamp.valueOf(userTimestamp)
            });
            for (int reportOffset = 0; reportOffset < REPORTS_PER_USER; reportOffset++) {
                reportArguments.add(new Object[]{
                        userId,
                        BUSINESS_DATE,
                        "scale report " + userOffset + "-" + reportOffset,
                        Timestamp.valueOf(BUSINESS_DATE.atTime(8, reportOffset))
                });
            }
        }

        jdbc.batchUpdate(
                """
                INSERT INTO users (
                    id, phone_number, telegram_user_id, full_name, department_name,
                    status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                userArguments
        );
        jdbc.batchUpdate(
                """
                INSERT INTO daily_reports (user_id, report_date, content, created_at)
                VALUES (?, ?, ?, ?)
                """,
                reportArguments
        );
        return new ScaleFixture(scope, List.copyOf(userIds));
    }

    private void assertFixtureCardinality(ScaleFixture fixture) {
        Integer selectedUsers = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM users
                WHERE lower(trim(department_name)) = lower(?)
                """,
                Integer.class,
                fixture.scope().departmentName()
        );
        Integer selectedReports = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM daily_reports report
                JOIN users user_account ON user_account.id = report.user_id
                WHERE lower(trim(user_account.department_name)) = lower(?)
                  AND report.report_date = ?
                """,
                Integer.class,
                fixture.scope().departmentName(),
                BUSINESS_DATE
        );

        assertThat(selectedUsers).isEqualTo(USER_COUNT);
        assertThat(selectedReports).isEqualTo(REPORT_COUNT);
    }

    private PlanEvidence explain(String workload, String query, Object... arguments) {
        List<String> lines = jdbc.query(
                "EXPLAIN (ANALYZE, BUFFERS) " + query,
                (resultSet, rowNumber) -> resultSet.getString(1),
                arguments
        );
        String plan = String.join(System.lineSeparator(), lines);
        Matcher matcher = EXECUTION_TIME.matcher(plan);
        assertThat(matcher.find()).as("execution time for %s", workload).isTrue();
        return new PlanEvidence(workload, plan, Duration.ofNanos((long) (Double.parseDouble(matcher.group(1)) * 1_000_000)));
    }

    private void assertCompletePlan(PlanEvidence evidence) {
        assertThat(evidence.plan())
                .as("plan for %s", evidence.workload())
                .contains("(actual time=", "rows=", "Buffers:", "Planning Time:", "Execution Time:");
    }

    private TeamReportReadService mappingOnlyService(
            List<User> persistedUsers,
            List<DailyReport> persistedReports,
            ScaleFixture fixture
    ) {
        List<User> users = persistedUsers.stream().map(this::detachedUser).toList();
        Map<Long, User> usersById = users.stream().collect(Collectors.toMap(User::getId, user -> user));
        List<DailyReport> reports = persistedReports.stream()
                .map(report -> detachedReport(report, usersById.get(report.getUser().getId())))
                .toList();
        UserRepository mappingUsers = mock(UserRepository.class);
        DailyReportRepository mappingReports = mock(DailyReportRepository.class);
        when(mappingUsers.findCurrentTeamMembers(fixture.scope().departmentName(), fixture.scope().unitName()))
                .thenReturn(users);
        when(mappingReports.findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(fixture.userIds(), BUSINESS_DATE))
                .thenReturn(reports);
        return new TeamReportReadService(mappingUsers, mappingReports, new TeamReportPolicy());
    }

    private User detachedUser(User source) {
        User copy = new User();
        copy.setId(source.getId());
        copy.setTelegramUserId(source.getTelegramUserId());
        copy.setFirstName(source.getFirstName());
        copy.setFullName(source.getFullName());
        copy.setDepartmentName(source.getDepartmentName());
        copy.setUnitName(source.getUnitName());
        copy.setStatus(source.getStatus());
        return copy;
    }

    private DailyReport detachedReport(DailyReport source, User owner) {
        DailyReport copy = new DailyReport();
        copy.setId(source.getId());
        copy.setUser(owner);
        copy.setReportDate(source.getReportDate());
        copy.setContent(source.getContent());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private List<Duration> measureSamples(
            int sampleCount,
            Supplier<TeamReportSummary> operation,
            java.util.function.Consumer<TeamReportSummary> resultAssertion
    ) {
        List<Duration> samples = new ArrayList<>(sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            long startedAt = System.nanoTime();
            TeamReportSummary summary = operation.get();
            samples.add(Duration.ofNanos(System.nanoTime() - startedAt));
            resultAssertion.accept(summary);
        }
        return List.copyOf(samples);
    }

    private void assertScaleSummary(TeamReportSummary summary) {
        assertThat(summary.totalEmployees()).isEqualTo(USER_COUNT);
        assertThat(summary.members()).hasSize(USER_COUNT);
        assertThat(summary.count(TeamReportStatus.SUBMITTED_ON_TIME)).isEqualTo((long) USER_COUNT);
        assertThat(summary.counts().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo((long) USER_COUNT);
        assertThat(summary.members()).allSatisfy(member -> {
            assertThat(member.status()).isEqualTo(TeamReportStatus.SUBMITTED_ON_TIME);
            assertThat(member.reports()).hasSize(REPORTS_PER_USER);
            assertThat(member.reports())
                    .extracting(TeamReportEvidence::createdAt)
                    .isSortedAccordingTo(Comparator.reverseOrder());
        });
        assertThat(summary.members().stream().mapToInt(member -> member.reports().size()).sum())
                .isEqualTo(REPORT_COUNT);
    }

    private Duration percentile95(List<Duration> samples) {
        assertThat(samples).hasSize(MEASURED_SAMPLE_COUNT);
        List<Duration> sorted = samples.stream().sorted().toList();
        int nearestRank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(nearestRank - 1);
    }

    private String teamDateBatchSql(int userCount) {
        return """
                SELECT report.id
                FROM daily_reports report
                WHERE report.user_id IN (%s)
                  AND report.report_date = ?
                ORDER BY report.created_at DESC, report.id DESC
                """.formatted(String.join(", ", java.util.Collections.nCopies(userCount, "?")));
    }

    private Object[] teamDateBatchArguments(ScaleFixture fixture) {
        Object[] arguments = new Object[fixture.userIds().size() + 1];
        for (int index = 0; index < fixture.userIds().size(); index++) {
            arguments[index] = fixture.userIds().get(index);
        }
        arguments[arguments.length - 1] = BUSINESS_DATE;
        return arguments;
    }

    private void printEvidence(
            ScaleFixture fixture,
            List<PlanEvidence> plans,
            List<Duration> javaMappingSamples,
            Duration javaMappingP95,
            List<Duration> endToEndSamples,
            Duration endToEndP95
    ) {
        String postgresVersion = jdbc.queryForObject("SHOW server_version", String.class);
        System.out.println("DBH-008 environment: image=" + POSTGRES.getDockerImageName()
                + ", PostgreSQL=" + postgresVersion
                + ", Java=" + System.getProperty("java.runtime.version"));
        System.out.println("DBH-008 fixture: department=" + fixture.scope().departmentName()
                + ", selectedUsers=" + USER_COUNT
                + ", selectedReports=" + REPORT_COUNT
                + ", reportsPerUser=" + REPORTS_PER_USER);
        for (PlanEvidence evidence : plans) {
            System.out.println("DBH-008 plan [" + evidence.workload() + "] executor="
                    + formatMillis(evidence.executionTime()));
            System.out.println(evidence.plan());
        }
        Duration totalPostgreSqlExecutorTime = plans.stream()
                .map(PlanEvidence::executionTime)
                .reduce(Duration.ZERO, Duration::plus);
        System.out.println("DBH-008 PostgreSQL executor total=" + formatMillis(totalPostgreSqlExecutorTime));
        System.out.println("DBH-008 Java mapping/aggregation samples=" + formatSamples(javaMappingSamples)
                + ", p95=" + formatMillis(javaMappingP95));
        System.out.println("DBH-008 end-to-end policy: warmups=" + WARMUP_COUNT
                + ", samples=" + MEASURED_SAMPLE_COUNT
                + ", nearestRank=" + (int) Math.ceil(0.95 * MEASURED_SAMPLE_COUNT));
        System.out.println("DBH-008 end-to-end samples=" + formatSamples(endToEndSamples)
                + ", p95=" + formatMillis(endToEndP95)
                + ", target<" + formatMillis(TARGET_P95));
    }

    private String formatSamples(List<Duration> samples) {
        return samples.stream().map(this::formatMillis).collect(Collectors.joining(", ", "[", "]"));
    }

    private String formatMillis(Duration duration) {
        return "%.3f ms".formatted(duration.toNanos() / 1_000_000.0);
    }

    private record ScaleFixture(TeamScope scope, List<Long> userIds) {
    }

    private record PlanEvidence(String workload, String plan, Duration executionTime) {
    }
}
