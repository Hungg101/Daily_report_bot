package com.example.dailyreportbot.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.IdentityAdminAction;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.LoggerFactory.getLogger;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramCommandServiceTest {

    private DailyReportService reportService;
    private UserProfileService userProfileService;
    private TeamReportReadService teamReportReadService;
    private IdentityAdministrationService identityAdministrationService;
    private TelegramCommandService service;
    private MutableClock clock;
    private TelegramBotProperties botProperties;

    @BeforeEach
    void setUp() {
        reportService = mock(DailyReportService.class);
        userProfileService = mock(UserProfileService.class);
        teamReportReadService = mock(TeamReportReadService.class);
        identityAdministrationService = mock(IdentityAdministrationService.class);
        clock = new MutableClock(Instant.parse("2026-06-17T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        botProperties = new TelegramBotProperties();
        when(userProfileService.findByTelegramUserId(12345L))
                .thenReturn(Optional.of(authorizedUser(true, false, "Engineering", "Platform")));
        service = new TelegramCommandService(
                reportService,
                userProfileService,
                teamReportReadService,
                identityAdministrationService,
                clock,
                botProperties
        );
    }

    @Test
    void shouldKeepVietnameseTextAsUtf8() {
        SendMessage response = service.createResponse(message("/start")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Xin chào! Đây là bot báo cáo công việc hằng ngày.");
    }

    @Test
    void shouldOpenConfiguredHttpsMiniAppOnlyInPrivateChat() {
        botProperties.setMiniAppEnabled(true);
        botProperties.setMiniAppUrl("https://miniapp.example.com/miniapp/index.html");

        SendMessage response = service.createResponse(message("/miniapp")).orElseThrow();

        assertThat(response.getText()).contains("Mở Mini App");
        assertThat(buttons(response)).singleElement().satisfies(button -> {
            assertThat(button.getWebApp()).isNotNull();
            assertThat(button.getWebApp().getUrl())
                    .isEqualTo("https://miniapp.example.com/miniapp/index.html");
            assertThat(button.getCallbackData()).isNull();
        });

        SendMessage groupResponse = service.createResponse(groupMessage("/miniapp")).orElseThrow();
        assertThat(groupResponse.getText()).contains("chat riêng");
        assertThat(buttons(groupResponse)).allSatisfy(button -> assertThat(button.getWebApp()).isNull());
    }

    @Test
    void shouldKeepMiniAppDisabledForMissingOrInvalidUrl() {
        botProperties.setMiniAppEnabled(true);
        for (String url : new String[]{null, "", "http://miniapp.example.com", "://bad"}) {
            botProperties.setMiniAppUrl(url);

            SendMessage response = service.createResponse(message("/miniapp")).orElseThrow();

            assertThat(response.getText()).contains("tạm tắt").contains("/report");
            assertThat(buttons(response)).allSatisfy(button -> assertThat(button.getWebApp()).isNull());
        }
    }

    @Test
    void shouldKeepMiniAppDisabledByDefaultEvenForConfiguredHttpsUrl() {
        botProperties.setMiniAppUrl("https://miniapp.example.com/miniapp/index.html");

        SendMessage response = service.createResponse(message("/miniapp")).orElseThrow();

        assertThat(response.getText()).contains("tạm tắt").contains("/report");
        assertThat(buttons(response)).allSatisfy(button -> assertThat(button.getWebApp()).isNull());
    }

    @Test
    void shouldShowEmployeeInformationWithoutSelfServiceLinking() {
        User user = new User();
        user.setTelegramUserId(12345L);
        user.setEmployeeCode("EMP001");
        user.setFullName("Nguyễn Văn A");
        user.setDepartmentName("Engineering");
        user.setUnitName("Platform");
        user.setStatus(UserStatus.ACTIVE);
        when(userProfileService.findByTelegramUserId(12345L)).thenReturn(Optional.of(user));

        SendMessage response = service.createResponse(message("/employeeinfo")).orElseThrow();

        assertThat(response.getText())
                .contains("Thông tin nhân viên")
                .contains("Telegram user ID: 12345")
                .contains("Mã nhân viên: EMP001")
                .contains("Họ tên: Nguyễn Văn A")
                .contains("Phòng ban: Engineering")
                .contains("Đơn vị: Platform");
        assertThat(service.createBotCommandMenu()).extracting(command -> command.getCommand())
                .contains("employeeinfo", "ai")
                .doesNotContain("link", "whoami");
        verifyNoInteractions(reportService);
    }

    @Test
    void shouldReadOnlyTheManagersCurrentTeamWithoutExposingReportEvidence() {
        User manager = authorizedUser(false, true, "Engineering", "Platform");
        TeamScope scope = new TeamScope("Engineering", null);
        when(userProfileService.findByTelegramUserId(12345L)).thenReturn(Optional.of(manager));
        when(teamReportReadService.readTeamSummary(
                scope,
                LocalDate.of(2026, 6, 17),
                clock.instant(),
                "Asia/Ho_Chi_Minh"
        )).thenReturn(teamSummary(scope));

        SendMessage response = service.createResponse(
                message("/teamstatus Finance Sales")
        ).orElseThrow();

        assertThat(response.getText())
                .contains("2026-06-17", "Engineering", "An", "DUE")
                .doesNotContain("Finance", "Sales", "sensitive report content", "private_username", "SECRET-EMP");
        assertThat(service.createBotCommandMenu()).extracting(command -> command.getCommand())
                .contains("teamstatus");
        assertThat(service.createResponse(message("/help")).orElseThrow().getText())
                .contains("/teamstatus");
        verify(teamReportReadService).readTeamSummary(
                scope,
                LocalDate.of(2026, 6, 17),
                clock.instant(),
                "Asia/Ho_Chi_Minh"
        );
        verifyNoInteractions(reportService);
    }

    @Test
    void shouldDenyNonPrivateMissingUnmappedEmployeeInactiveAndMissingDepartmentCallers() {
        User employee = authorizedUser(false, false, "Engineering", "Platform");
        User inactiveManager = authorizedUser(false, true, "Engineering", "Platform");
        inactiveManager.setStatus(UserStatus.INACTIVE);
        User managerWithoutDepartment = authorizedUser(false, true, " ", "Platform");
        when(userProfileService.findByTelegramUserId(20001L)).thenReturn(Optional.empty());
        when(userProfileService.findByTelegramUserId(20002L)).thenReturn(Optional.of(employee));
        when(userProfileService.findByTelegramUserId(20003L)).thenReturn(Optional.of(inactiveManager));
        when(userProfileService.findByTelegramUserId(20004L)).thenReturn(Optional.of(managerWithoutDepartment));

        List<SendMessage> denied = List.of(
                service.createResponse(groupMessage("/teamstatus")).orElseThrow(),
                service.createResponse(messageWithoutSender("/teamstatus")).orElseThrow(),
                service.createResponse(message("/teamstatus", 1001L, 20001L)).orElseThrow(),
                service.createResponse(message("/teamstatus", 1001L, 20002L)).orElseThrow(),
                service.createResponse(message("/teamstatus", 1001L, 20003L)).orElseThrow(),
                service.createResponse(message("/teamstatus", 1001L, 20004L)).orElseThrow()
        );

        assertThat(denied).extracting(SendMessage::getText)
                .allSatisfy(text -> assertThat(text).contains("không có quyền").doesNotContain("Engineering", "Platform"));
        verifyNoInteractions(teamReportReadService, reportService);
    }

    @Test
    void shouldGiveAdministratorsGlobalScopeAndAdminPrecedence() {
        TeamScope organizationScope = new TeamScope("Toàn hệ thống", null);
        User admin = authorizedUser(true, false, "Engineering", "Platform");
        User dualRole = authorizedUser(true, true, "Engineering", "Platform");
        User adminWithoutDepartment = authorizedUser(true, false, " ", "Platform");
        when(userProfileService.findByTelegramUserId(30001L)).thenReturn(Optional.of(admin));
        when(userProfileService.findByTelegramUserId(30002L)).thenReturn(Optional.of(dualRole));
        when(userProfileService.findByTelegramUserId(30003L)).thenReturn(Optional.of(adminWithoutDepartment));
        when(teamReportReadService.readOrganizationSummary(
                LocalDate.of(2026, 6, 17),
                clock.instant(),
                "Asia/Ho_Chi_Minh"
        )).thenReturn(teamSummary(organizationScope));

        SendMessage adminResponse = service.createResponse(
                message("/teamstatus Finance", 1001L, 30001L)
        ).orElseThrow();
        SendMessage dualRoleResponse = service.createResponse(
                message("/teamstatus Sales", 1001L, 30002L)
        ).orElseThrow();
        SendMessage noDepartmentResponse = service.createResponse(
                message("/teamstatus Finance", 1001L, 30003L)
        ).orElseThrow();

        assertThat(adminResponse.getText()).contains("Toàn hệ thống").doesNotContain("Finance", "Platform");
        assertThat(dualRoleResponse.getText()).contains("Toàn hệ thống").doesNotContain("Sales", "Platform");
        assertThat(noDepartmentResponse.getText()).contains("Toàn hệ thống");
        verify(teamReportReadService, times(3)).readOrganizationSummary(
                LocalDate.of(2026, 6, 17),
                clock.instant(),
                "Asia/Ho_Chi_Minh"
        );
        verifyNoInteractions(reportService);
    }

    @Test
    void shouldShowCompleteManagementHelpAndClearPendingReportSession() {
        service.createResponse(message("/report"));

        SendMessage response = service.createResponse(message("/manage")).orElseThrow();

        assertThat(response.getText()).contains(
                "/manage users [keyword]",
                "/manage user-info <telegramUserId>",
                "/manage reports <telegramUserId>|<YYYY-MM-DD>",
                "/manage recent-reports <telegramUserId>",
                "/manage report-info <reportId>",
                "/manage department-reports <departmentName>|<YYYY-MM-DD>",
                "/manage missing <departmentName>|<YYYY-MM-DD>",
                "/manage organization-summary <YYYY-MM-DD>",
                "/manage audit <telegramUserId>",
                "/manage user-update <telegramUserId>|<employeeCode>|<fullName>|<unitName>|<reason>",
                "/manage user-status <telegramUserId>|<ACTIVE_OR_INACTIVE>|<reason>",
                "/manage user-department <telegramUserId>|<departmentName>|<unitName>|<reason>",
                "/manage report-edit <reportId>|<newContent>|<reason>",
                "/manage report-delete <reportId>|<reason>",
                "/manage role <telegramUserId>|<MANAGER_OR_ADMIN>|<GRANT_OR_REVOKE>|<reason>"
        );
        assertThat(service.createResponse(message("orphaned draft text")).orElseThrow().getText())
                .contains("/report");
        verifyNoInteractions(identityAdministrationService);
    }

    @Test
    void shouldDenyIneligibleManagementActorsBeforeHelpUsageParsingOrCallbackDispatch() {
        User employee = authorizedUser(false, false, "Engineering", "Platform");
        User inactiveAdmin = authorizedUser(true, false, "Engineering", "Platform");
        inactiveAdmin.setStatus(UserStatus.INACTIVE);
        User managerWithoutDepartment = authorizedUser(false, true, " ", "Platform");
        when(userProfileService.findByTelegramUserId(20001L)).thenReturn(Optional.empty());
        when(userProfileService.findByTelegramUserId(20002L)).thenReturn(Optional.of(employee));
        when(userProfileService.findByTelegramUserId(20003L)).thenReturn(Optional.of(inactiveAdmin));
        when(userProfileService.findByTelegramUserId(20004L)).thenReturn(Optional.of(managerWithoutDepartment));

        List<SendMessage> denied = List.of(
                service.createResponse(message("/manage", 1001L, 20001L)).orElseThrow(),
                service.createResponse(message("/manage user-info", 1001L, 20002L)).orElseThrow(),
                service.createResponse(message("/manage unknown", 1001L, 20003L)).orElseThrow(),
                service.createResponse(callback("command:/manage users", 1001L, 20004L)).orElseThrow()
        );

        assertThat(denied).extracting(SendMessage::getText)
                .containsOnly(denied.get(0).getText())
                .allSatisfy(text -> assertThat(text).doesNotContain("/manage", "<telegramUserId>"));
        verifyNoInteractions(identityAdministrationService);
    }

    @Test
    void shouldExposeStaticManagementKeyboardAndPreserveCallbackArguments() {
        when(identityAdministrationService.readUsers(12345L)).thenReturn(noManagementData());

        SendMessage manage = service.createResponse(message("/manage")).orElseThrow();
        SendMessage help = service.createResponse(message("/help")).orElseThrow();

        assertThat(buttons(manage)).extracting(InlineKeyboardButton::getText).containsExactly(
                "👥 Người dùng",
                "👤 Thông tin user",
                "📑 Báo cáo phòng/ngày",
                "❌ Chưa nộp",
                "🕘 Báo cáo gần nhất",
                "📊 Trạng thái đội",
                "ℹ️ Trợ giúp"
        );
        assertThat(callbackData(manage, "👥 Người dùng")).isEqualTo("command:/manage users");
        assertThat(callbackData(manage, "👤 Thông tin user")).isEqualTo("command:/manage user-info");
        assertThat(callbackData(manage, "📑 Báo cáo phòng/ngày"))
                .isEqualTo("command:/manage department-reports");
        assertThat(callbackData(manage, "❌ Chưa nộp")).isEqualTo("command:/manage missing");
        assertThat(callbackData(manage, "🕘 Báo cáo gần nhất"))
                .isEqualTo("command:/manage recent-reports");
        assertThat(callbackData(manage, "📊 Trạng thái đội")).isEqualTo("command:/teamstatus");
        assertThat(callbackData(manage, "ℹ️ Trợ giúp")).isEqualTo("command:/help");
        assertThat(callbackData(help, "👥 Quản trị")).isEqualTo("command:/manage");

        SendMessage callbackResponse = service.createResponse(callback("command:/manage users")).orElseThrow();
        assertThat(callbackResponse.getText()).containsIgnoringCase("chưa có dữ liệu");
        verify(identityAdministrationService).readUsers(12345L);
    }

    @Test
    void shouldDispatchEveryManagementReadAndDepartmentChangeWithExactArguments() {
        ManagementReadResult noData = noManagementData();
        when(identityAdministrationService.readUsers(12345L)).thenReturn(noData);
        when(identityAdministrationService.readUser(12345L, 20001L)).thenReturn(noData);
        when(identityAdministrationService.readReports(
                12345L, 20001L, LocalDate.of(2026, 7, 2)
        )).thenReturn(noData);
        when(identityAdministrationService.readRecentReports(12345L, 20001L)).thenReturn(noData);
        when(identityAdministrationService.readReport(12345L, 301L)).thenReturn(noData);
        when(identityAdministrationService.readDepartmentReports(
                12345L, "Engineering", LocalDate.of(2026, 7, 2)
        )).thenReturn(noData);
        when(identityAdministrationService.readOrganizationReports(
                12345L, LocalDate.of(2026, 7, 2)
        )).thenReturn(noData);
        when(identityAdministrationService.readAudit(12345L, 20001L)).thenReturn(noData);
        when(identityAdministrationService.changeDepartment(
                12345L, 20001L, "Finance", "Core", "transfer reason"
        )).thenReturn(new IdentityAdminResult(IdentityAdminStatus.UPDATED, null));

        List<SendMessage> readResponses = List.of(
                service.createResponse(message("/manage users employee")).orElseThrow(),
                service.createResponse(message("/manage user-info 20001")).orElseThrow(),
                service.createResponse(message("/manage reports 20001|2026-07-02")).orElseThrow(),
                service.createResponse(message("/manage recent-reports 20001")).orElseThrow(),
                service.createResponse(message("/manage report-info 301")).orElseThrow(),
                service.createResponse(message(
                        "/manage department-reports   Engineering  |2026-07-02"
                )).orElseThrow(),
                service.createResponse(message("/manage missing Engineering|2026-07-02")).orElseThrow(),
                service.createResponse(message("/manage organization-summary 2026-07-02")).orElseThrow(),
                service.createResponse(message("/manage audit 20001")).orElseThrow()
        );
        SendMessage transfer = service.createResponse(message(
                "/manage user-department 20001| Finance | Core |transfer reason"
        )).orElseThrow();

        assertThat(readResponses).extracting(SendMessage::getText)
                .allSatisfy(text -> assertThat(text).containsIgnoringCase("chưa có dữ liệu"));
        assertThat(transfer.getText()).contains("Đã cập nhật").doesNotContain("transfer reason");
        verify(identityAdministrationService).readUsers(12345L);
        verify(identityAdministrationService).readUser(12345L, 20001L);
        verify(identityAdministrationService).readReports(12345L, 20001L, LocalDate.of(2026, 7, 2));
        verify(identityAdministrationService).readRecentReports(12345L, 20001L);
        verify(identityAdministrationService).readReport(12345L, 301L);
        verify(identityAdministrationService, times(2)).readDepartmentReports(
                12345L, "Engineering", LocalDate.of(2026, 7, 2)
        );
        verify(identityAdministrationService).readOrganizationReports(12345L, LocalDate.of(2026, 7, 2));
        verify(identityAdministrationService).readAudit(12345L, 20001L);
        verify(identityAdministrationService).changeDepartment(
                12345L, 20001L, "Finance", "Core", "transfer reason"
        );
    }

    @Test
    void shouldRejectStrictlyInvalidManagementInputBeforeServiceInvocation() {
        String oversizedReason = "r".repeat(1001);
        String oversizedProfile = "p".repeat(256);
        List<String> invalidCommands = List.of(
                "/manage unknown",
                "/manage user-info 20001|extra",
                "/manage user-info 0",
                "/manage reports 20001|2026-7-02",
                "/manage reports 20001|2026-02-30",
                "/manage user-status 20001|PAUSED|reason",
                "/manage role 20001|OWNER|GRANT|reason",
                "/manage role 20001|ADMIN|PROMOTE|reason",
                "/manage report-edit 301||reason",
                "/manage report-delete 301|",
                "/manage report-delete 301|" + oversizedReason,
                "/manage user-update 20001|EMP-1|" + oversizedProfile + "|Core|reason",
                "/manage user-department 20001||Core|reason"
        );

        assertThat(invalidCommands).allSatisfy(command -> {
            String response = service.createResponse(message(command)).orElseThrow().getText();
            assertThat(response).contains("không hợp lệ")
                    .doesNotContain(oversizedReason, oversizedProfile);
        });
        verifyNoInteractions(identityAdministrationService);
    }

    @Test
    void shouldBoundManagementUsersReportsAndAuditWithoutLeakingPrivateFields() {
        List<User> users = new ArrayList<>();
        for (int index = 51; index >= 1; index -= 1) {
            users.add(managementUser(index));
        }
        User owner = users.get(users.size() - 1);
        List<DailyReport> reports = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> managementReport(700L + index, owner, index))
                .toList();
        List<IdentityAuditEvent> events = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> managementAuditEvent(owner, index))
                .toList();
        when(identityAdministrationService.readUsers(12345L)).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS, users, List.of(), List.of()
        ));
        when(identityAdministrationService.readReports(
                12345L, owner.getTelegramUserId(), LocalDate.of(2026, 7, 2)
        )).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS, List.of(owner), reports, List.of()
        ));
        when(identityAdministrationService.readAudit(12345L, owner.getTelegramUserId()))
                .thenReturn(new ManagementReadResult(
                        IdentityAdminStatus.SUCCESS, List.of(owner), List.of(), events
                ));

        String usersText = service.createResponse(message("/manage users")).orElseThrow().getText();
        String reportsText = service.createResponse(message(
                "/manage reports " + owner.getTelegramUserId() + "|2026-07-02"
        )).orElseThrow().getText();
        String auditText = service.createResponse(message(
                "/manage audit " + owner.getTelegramUserId()
        )).orElseThrow().getText();

        assertThat(usersText)
                .contains("EMP-001", "EMP-002", "người dùng chưa hiển thị")
                .doesNotContain("EMP-051", "SECRET_PHONE", "private_username", "987654321");
        assertThat(usersText.indexOf("EMP-001")).isLessThan(usersText.indexOf("EMP-002"));
        assertThat(reportsText)
                .contains("report-preview-1", "report-preview-5")
                .doesNotContain("report-preview-6");
        assertThat(auditText)
                .contains("audit-reason-01", "audit-reason-10")
                .doesNotContain(
                        "audit-reason-11",
                        "SECRET_BEFORE_STATE",
                        "SECRET_AFTER_STATE",
                        "private report body",
                        "contentSha256"
                );
    }

    @Test
    void shouldFormatReportDepartmentMissingAndOrganizationReadsSafely() {
        User submitted = managementUser(1);
        User missing = managementUser(2);
        DailyReport first = managementReport(801L, submitted, 1);
        DailyReport second = managementReport(802L, submitted, 2);
        DailyReport detail = managementReport(803L, submitted, 3);
        detail.setContent("authorized-detail-" + "x".repeat(4_000));
        ManagementReadResult department = new ManagementReadResult(
                IdentityAdminStatus.SUCCESS,
                List.of(submitted, missing),
                List.of(first, second),
                List.of()
        );
        User unassigned = managementUser(3);
        unassigned.setDepartmentName(" ");
        when(identityAdministrationService.readReport(12345L, 803L)).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS, List.of(submitted), List.of(detail), List.of()
        ));
        when(identityAdministrationService.readDepartmentReports(
                12345L, "Engineering", LocalDate.of(2026, 7, 2)
        )).thenReturn(department);
        when(identityAdministrationService.readOrganizationReports(
                12345L, LocalDate.of(2026, 7, 2)
        )).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS,
                List.of(submitted, missing, unassigned),
                List.of(first, second),
                List.of()
        ));

        String detailText = service.createResponse(message("/manage report-info 803"))
                .orElseThrow().getText();
        String departmentText = service.createResponse(message(
                "/manage department-reports Engineering|2026-07-02"
        )).orElseThrow().getText();
        String missingText = service.createResponse(message(
                "/manage missing Engineering|2026-07-02"
        )).orElseThrow().getText();
        String organizationText = service.createResponse(message(
                "/manage organization-summary 2026-07-02"
        )).orElseThrow().getText();

        assertThat(detailText).hasSizeLessThanOrEqualTo(3_500)
                .contains("Report ID: 803", "EMP-001", "Employee 1", "authorized-detail-", "đã được rút gọn")
                .doesNotContain("SECRET_PHONE", "private_username", "987654321");
        assertThat(departmentText)
                .contains("Tổng người dùng: 2", "Đã nộp: 1", "Chưa nộp: 1", "Tổng báo cáo: 2", "ID 801", "ID 802");
        assertThat(missingText).contains("EMP-002", "Employee 2")
                .doesNotContain("EMP-001", "report-preview-1", "report-preview-2");
        assertThat(organizationText)
                .contains("Tổng người dùng: 3", "Đã nộp: 1", "Chưa nộp: 2", "Tổng báo cáo: 2", "Chưa phân phòng")
                .doesNotContain("report-preview-1", "report-preview-2", "SECRET_PHONE");
    }

    @Test
    void shouldKeepUserAndDepartmentResultsWithinBudgetWithExactOmissionCounts() {
        List<User> longUsers = new ArrayList<>();
        for (int index = 1; index <= 50; index += 1) {
            User user = managementUser(index);
            user.setEmployeeCode(("EMP-%03d-".formatted(index)) + "e".repeat(245));
            user.setFullName("n".repeat(255));
            longUsers.add(user);
        }
        when(identityAdministrationService.readUsers(12345L)).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS, longUsers, List.of(), List.of()
        ));

        List<User> departmentUsers = new ArrayList<>();
        List<DailyReport> departmentReports = new ArrayList<>();
        for (int index = 1; index <= 20; index += 1) {
            User user = managementUser(index);
            departmentUsers.add(user);
            for (int reportIndex = 1; reportIndex <= 2; reportIndex += 1) {
                DailyReport report = managementReport(1_000L + index * 10L + reportIndex, user, reportIndex);
                report.setContent("d".repeat(300));
                departmentReports.add(report);
            }
        }
        when(identityAdministrationService.readDepartmentReports(
                12345L, "Engineering", LocalDate.of(2026, 7, 2)
        )).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS, departmentUsers, departmentReports, List.of()
        ));
        User reportOwner = managementUser(60);
        List<DailyReport> longReports = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(index -> {
                    DailyReport report = managementReport(2_000L + index, reportOwner, index);
                    report.setDepartment("p".repeat(255));
                    report.setUnit("u".repeat(255));
                    report.setContent("c".repeat(300));
                    return report;
                })
                .toList();
        when(identityAdministrationService.readReports(
                12345L, reportOwner.getTelegramUserId(), LocalDate.of(2026, 7, 2)
        )).thenReturn(new ManagementReadResult(
                IdentityAdminStatus.SUCCESS, List.of(reportOwner), longReports, List.of()
        ));
        List<IdentityAuditEvent> longAudit = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(index -> new IdentityAuditEvent(
                        IdentityAdminAction.UPDATE_PROFILE,
                        "a".repeat(255),
                        reportOwner,
                        null,
                        "r".repeat(1_000),
                        "private before",
                        "private after",
                        LocalDateTime.of(2026, 7, 2, 10, index)
                ))
                .toList();
        when(identityAdministrationService.readAudit(12345L, reportOwner.getTelegramUserId()))
                .thenReturn(new ManagementReadResult(
                        IdentityAdminStatus.SUCCESS, List.of(reportOwner), List.of(), longAudit
                ));

        String usersText = service.createResponse(message("/manage users")).orElseThrow().getText();
        String departmentText = service.createResponse(message(
                "/manage department-reports Engineering|2026-07-02"
        )).orElseThrow().getText();
        String reportsText = service.createResponse(message(
                "/manage reports " + reportOwner.getTelegramUserId() + "|2026-07-02"
        )).orElseThrow().getText();
        String auditText = service.createResponse(message(
                "/manage audit " + reportOwner.getTelegramUserId()
        )).orElseThrow().getText();

        assertThat(usersText).hasSizeLessThanOrEqualTo(3_500);
        assertThat(departmentText).hasSizeLessThanOrEqualTo(3_500);
        assertThat(reportsText).hasSizeLessThanOrEqualTo(3_500);
        assertThat(auditText).hasSizeLessThanOrEqualTo(3_500);
        Matcher omission = Pattern.compile("Đã lược bỏ (\\d+) người dùng và (\\d+) báo cáo")
                .matcher(departmentText);
        assertThat(omission.find()).isTrue();
        int shownUsers = departmentText.split("Trạng thái nộp:", -1).length - 1;
        int shownReports = departmentText.split("  • ID ", -1).length - 1;
        assertThat(Integer.parseInt(omission.group(1))).isEqualTo(20 - shownUsers);
        assertThat(Integer.parseInt(omission.group(2))).isEqualTo(40 - shownReports);
    }

    @Test
    void shouldDispatchEveryPrivateManageSubActionWithoutEchoingSensitiveInput() {
        when(identityAdministrationService.updateUser(
                12345L, 20001L, "EMP-2", "New Name", "Core", "profile reason"
        )).thenReturn(new IdentityAdminResult(IdentityAdminStatus.UPDATED, null));
        when(identityAdministrationService.changeStatus(
                12345L, 20001L, UserStatus.INACTIVE, "status reason"
        )).thenReturn(new IdentityAdminResult(IdentityAdminStatus.UPDATED, null));
        when(identityAdministrationService.editReport(
                12345L, 301L, "sensitive report body", "edit reason"
        )).thenReturn(new IdentityAdminResult(IdentityAdminStatus.UPDATED, null));
        when(identityAdministrationService.deleteReport(
                12345L, 302L, "delete reason"
        )).thenReturn(new IdentityAdminResult(IdentityAdminStatus.DELETED, null));
        when(identityAdministrationService.changeRole(
                12345L,
                20001L,
                IdentityAdministrationService.Role.ADMIN,
                IdentityAdministrationService.RoleChange.GRANT,
                "role reason"
        )).thenReturn(new IdentityAdminResult(IdentityAdminStatus.UPDATED, null));

        List<SendMessage> responses = List.of(
                service.createResponse(message(
                        "/manage user-update 20001|EMP-2|New Name|Core|profile reason"
                )).orElseThrow(),
                service.createResponse(message(
                        "/manage user-status 20001|INACTIVE|status reason"
                )).orElseThrow(),
                service.createResponse(message(
                        "/manage report-edit 301|sensitive report body|edit reason"
                )).orElseThrow(),
                service.createResponse(message(
                        "/manage report-delete 302|delete reason"
                )).orElseThrow(),
                service.createResponse(message(
                        "/manage role 20001|ADMIN|GRANT|role reason"
                )).orElseThrow()
        );

        assertThat(responses).extracting(SendMessage::getText).containsExactly(
                "Đã cập nhật thành công.",
                "Đã cập nhật thành công.",
                "Đã cập nhật thành công.",
                "Đã xóa thành công.",
                "Đã cập nhật thành công."
        ).allSatisfy(text -> assertThat(text).doesNotContain(
                "sensitive report body",
                "profile reason",
                "status reason",
                "edit reason",
                "delete reason",
                "role reason"
        ));
        verify(identityAdministrationService).updateUser(
                12345L, 20001L, "EMP-2", "New Name", "Core", "profile reason"
        );
        verify(identityAdministrationService).changeStatus(
                12345L, 20001L, UserStatus.INACTIVE, "status reason"
        );
        verify(identityAdministrationService).editReport(
                12345L, 301L, "sensitive report body", "edit reason"
        );
        verify(identityAdministrationService).deleteReport(12345L, 302L, "delete reason");
        verify(identityAdministrationService).changeRole(
                12345L,
                20001L,
                IdentityAdministrationService.Role.ADMIN,
                IdentityAdministrationService.RoleChange.GRANT,
                "role reason"
        );
        assertThat(service.createBotCommandMenu()).extracting(command -> command.getCommand())
                .contains("manage");
        assertThat(service.createResponse(message("/help")).orElseThrow().getText())
                .contains("/manage");
    }

    @Test
    void shouldRejectNonPrivateMissingSenderAndMalformedManageCommandsBeforeService() {
        SendMessage group = service.createResponse(groupMessage(
                "/manage report-delete 302|reason"
        )).orElseThrow();
        SendMessage missingSender = service.createResponse(messageWithoutSender(
                "/manage report-delete 302|reason"
        )).orElseThrow();
        SendMessage malformed = service.createResponse(message(
                "/manage role invalid|ADMIN|GRANT|reason"
        )).orElseThrow();

        assertThat(group.getText()).contains("không có quyền");
        assertThat(missingSender.getText()).contains("không có quyền");
        assertThat(malformed.getText()).contains("không hợp lệ");
        verifyNoInteractions(identityAdministrationService);
    }

    @Test
    void shouldMapManagementOutcomesToBoundedMessages() {
        when(identityAdministrationService.deleteReport(12345L, 302L, "reason"))
                .thenReturn(
                        new IdentityAdminResult(IdentityAdminStatus.NO_CHANGE, null),
                        new IdentityAdminResult(IdentityAdminStatus.ACCESS_DENIED, null),
                        new IdentityAdminResult(IdentityAdminStatus.REPORT_NOT_FOUND, null),
                        new IdentityAdminResult(IdentityAdminStatus.IDENTITY_CONFLICT, null),
                        new IdentityAdminResult(IdentityAdminStatus.FAILED, null)
                );

        List<String> responses = java.util.stream.IntStream.range(0, 5)
                .mapToObj(ignored -> service.createResponse(message(
                        "/manage report-delete 302|reason"
                )).orElseThrow().getText())
                .toList();

        assertThat(responses.get(0)).contains("Không có thay đổi");
        assertThat(responses.get(1)).contains("không có quyền");
        assertThat(responses.get(2)).contains("Không tìm thấy");
        assertThat(responses.get(3)).contains("đã thay đổi");
        assertThat(responses.get(4)).contains("Không thể thực hiện");
        assertThat(responses).allSatisfy(text -> assertThat(text).doesNotContain("reason"));
    }

    @Test
    void shouldLogOnlyActorAndErrorTypeWhenManagementActionFails() {
        String body = "PRIVATE_MANAGEMENT_BODY_4d1e";
        String reason = "PRIVATE_MANAGEMENT_REASON_8b2c";
        String token = "PRIVATE_MANAGEMENT_TOKEN_7f9a";
        String fullCommand = "/manage report-edit 301|" + body + "|" + reason;
        String exceptionMessage = fullCommand + "|token=" + token;
        when(identityAdministrationService.editReport(12345L, 301L, body, reason))
                .thenThrow(new RuntimeException(exceptionMessage));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(TelegramCommandService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        SendMessage response;
        try {
            response = service.createResponse(message(fullCommand)).orElseThrow();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.getText()).isEqualTo(
                "Kh\u00f4ng th\u1ec3 th\u1ef1c hi\u1ec7n thao t\u00e1c qu\u1ea3n tr\u1ecb l\u00fac n\u00e0y."
        ).doesNotContain(body, reason, token, fullCommand, exceptionMessage);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("telegramUserId=12345", "errorType=RuntimeException")
                    .doesNotContain(body, reason, token, fullCommand, exceptionMessage);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void shouldWalkWizardAndPreviewWithoutSaving() {
        when(userProfileService.findByTelegramUserId(12345L))
                .thenReturn(Optional.of(user(12345L, "Nguyễn Văn A")));

        SendMessage titlePrompt = service.createResponse(message("/report")).orElseThrow();
        SendMessage collaboratorPrompt = service.createResponse(message("Kế hoạch quý III")).orElseThrow();
        SendMessage contentPrompt = service.createResponse(message("Trần Thị B")).orElseThrow();
        SendMessage preview = service.createResponse(message("Hoàn thành API")).orElseThrow();

        assertThat(titlePrompt.getText()).contains("Tiêu đề");
        assertThat(collaboratorPrompt.getText()).contains("Người cùng thực hiện");
        assertThat(buttons(collaboratorPrompt)).extracting(InlineKeyboardButton::getText)
                .contains("👤 Không - Tôi làm một mình");
        assertThat(contentPrompt.getText()).contains("Nội dung báo cáo");
        assertThat(preview.getText())
                .contains("📋 CHI TIẾT BÁO CÁO")
                .contains("Giờ gửi: 2026-06-17 08:00")
                .contains("Tiêu đề: Kế hoạch quý III")
                .contains("Người thực hiện: Nguyễn Văn A")
                .contains("Người cùng thực hiện: Trần Thị B")
                .contains("Nội dung:\nHoàn thành API");
        assertThat(buttons(preview)).extracting(InlineKeyboardButton::getText)
                .containsExactly(
                        "✏️ Tiêu đề",
                        "👥 Người cùng thực hiện",
                        "📝 Nội dung",
                        "✅ Xác nhận",
                        "❌ Hủy"
                );
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldStartWizardFromInlineReportButton() {
        SendMessage start = service.createResponse(message("/start")).orElseThrow();

        String reportCallback = callbackData(start, "📝 Nhập báo cáo");
        SendMessage response = service.createResponse(callback(reportCallback)).orElseThrow();

        assertThat(response.getText()).contains("Tiêu đề");
        verifyNoInteractions(reportService);
    }

    @Test
    void shouldUseSoloButtonAndPreviewNoCollaborator() {
        when(userProfileService.findByTelegramUserId(12345L))
                .thenReturn(Optional.of(user(12345L, "Nguyễn Văn A")));

        service.createResponse(message("/report"));
        SendMessage collaboratorPrompt = service.createResponse(message("Báo cáo solo")).orElseThrow();
        String soloCallback = callbackData(collaboratorPrompt, "👤 Không - Tôi làm một mình");

        SendMessage contentPrompt = service.createResponse(callback(soloCallback)).orElseThrow();
        SendMessage preview = service.createResponse(message("Tự hoàn thành công việc")).orElseThrow();

        assertThat(contentPrompt.getText()).contains("Nội dung báo cáo");
        assertThat(preview.getText()).contains("Người cùng thực hiện: Không có");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldRejectBlankAndOverlongInputWithoutAdvancing() {
        when(userProfileService.findByTelegramUserId(12345L))
                .thenReturn(Optional.of(user(12345L, "Nguyễn Văn A")));

        service.createResponse(message("/report"));
        assertThat(service.createResponse(message("   ")).orElseThrow().getText()).contains("Tiêu đề");
        assertThat(service.createResponse(message("T".repeat(201))).orElseThrow().getText()).contains("200");

        assertThat(service.createResponse(message("Tiêu đề hợp lệ")).orElseThrow().getText())
                .contains("Người cùng thực hiện");
        assertThat(service.createResponse(message("  ")).orElseThrow().getText())
                .contains("Người cùng thực hiện");
        assertThat(service.createResponse(message("C".repeat(501))).orElseThrow().getText()).contains("500");

        assertThat(service.createResponse(message("Cộng sự hợp lệ")).orElseThrow().getText())
                .contains("Nội dung báo cáo");
        assertThat(service.createResponse(message("\t")).orElseThrow().getText()).contains("Nội dung");
        assertThat(service.createResponse(message("N".repeat(3001))).orElseThrow().getText()).contains("3000");

        SendMessage preview = service.createResponse(message("Nội dung hợp lệ")).orElseThrow();
        assertThat(preview.getText()).contains("📋 CHI TIẾT BÁO CÁO");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldGuideUnsupportedSlashCommandsWithoutAdvancingAnyDraftStep() {
        when(userProfileService.findByTelegramUserId(12345L))
                .thenReturn(Optional.of(user(7L, 12345L, "Nguyễn Văn A")));

        service.createResponse(message("/report"));
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");

        service.createResponse(message("Tiêu đề"));
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");

        service.createResponse(message("Cộng sự"));
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");

        SendMessage preview = service.createResponse(message("Nội dung")).orElseThrow();
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");

        service.createResponse(callback(callbackData(preview, "✏️ Tiêu đề")));
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");
        preview = service.createResponse(message("Tiêu đề mới")).orElseThrow();

        service.createResponse(callback(callbackData(preview, "👥 Người cùng thực hiện")));
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");
        preview = service.createResponse(message("Cộng sự mới")).orElseThrow();

        service.createResponse(callback(callbackData(preview, "📝 Nội dung")));
        assertThat(service.createResponse(message("/unsupported")).orElseThrow().getText())
                .contains("/help", "/report");
        SendMessage updatedPreview = service.createResponse(message("Nội dung mới")).orElseThrow();

        assertThat(updatedPreview.getText())
                .contains("Tiêu đề: Tiêu đề mới")
                .contains("Người cùng thực hiện: Cộng sự mới")
                .contains("Nội dung:\nNội dung mới");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldConfirmLatestPreviewExactlyOnceAndAllowAnotherReport() {
        String storedContent = """
                Tiêu đề: Kế hoạch quý III
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Trần Thị B
                Nội dung:
                Hoàn thành API""";
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(storedContent), anyString(), any(Instant.class)))
                .thenReturn(DailyReportSubmissionStatus.SAVED);
        SendMessage preview = reachPreview("Kế hoạch quý III", "Trần Thị B", "Hoàn thành API");
        String confirmCallback = callbackData(preview, "✅ Xác nhận");
        clock.advance(Duration.ofMinutes(5));

        SendMessage response = service.createResponse(callback(confirmCallback)).orElseThrow();

        assertThat(response.getText())
                .contains("Đã lưu báo cáo")
                .contains("Giờ gửi: 2026-06-17 08:05")
                .contains(storedContent);
        verify(reportService).submitToday(12345L, 12345L, storedContent, "Trần Thị B", clock.instant());

        service.createResponse(callback(confirmCallback));
        verify(reportService, times(1)).submitToday(12345L, 12345L, storedContent, "Trần Thị B", clock.instant());
        assertThat(service.createResponse(message("/report")).orElseThrow().getText()).contains("Tiêu đề");
        assertThat(service.createResponse(callback(confirmCallback)).orElseThrow().getText())
                .contains("hết hiệu lực");
        assertThat(service.createResponse(message("Báo cáo mới")).orElseThrow().getText())
                .contains("Người cùng thực hiện");
        verify(reportService, times(1)).submitToday(12345L, 12345L, storedContent, "Trần Thị B", clock.instant());
    }

    @Test
    void shouldEditEachFieldAndReturnToPreview() {
        SendMessage preview = reachPreview("Tiêu đề cũ", "Cộng sự cũ", "Nội dung cũ");

        SendMessage titlePrompt = service.createResponse(callback(
                callbackData(preview, "✏️ Tiêu đề")
        )).orElseThrow();
        assertThat(titlePrompt.getText()).contains("Tiêu đề");
        assertThat(service.createResponse(message("   ")).orElseThrow().getText()).contains("Tiêu đề");
        assertThat(service.createResponse(message("T".repeat(201))).orElseThrow().getText()).contains("200");
        preview = service.createResponse(message("Tiêu đề mới")).orElseThrow();
        assertThat(preview.getText())
                .contains("Tiêu đề: Tiêu đề mới")
                .contains("Người cùng thực hiện: Cộng sự cũ")
                .contains("Nội dung:\nNội dung cũ");

        SendMessage collaboratorPrompt = service.createResponse(callback(
                callbackData(preview, "👥 Người cùng thực hiện")
        )).orElseThrow();
        assertThat(collaboratorPrompt.getText()).contains("Người cùng thực hiện");
        assertThat(buttons(collaboratorPrompt)).extracting(InlineKeyboardButton::getText)
                .contains("👤 Không - Tôi làm một mình");
        assertThat(service.createResponse(message("   ")).orElseThrow().getText())
                .contains("Người cùng thực hiện");
        assertThat(service.createResponse(message("C".repeat(501))).orElseThrow().getText()).contains("500");
        preview = service.createResponse(message("Cộng sự mới")).orElseThrow();
        assertThat(preview.getText())
                .contains("Tiêu đề: Tiêu đề mới")
                .contains("Người cùng thực hiện: Cộng sự mới")
                .contains("Nội dung:\nNội dung cũ");

        collaboratorPrompt = service.createResponse(callback(
                callbackData(preview, "👥 Người cùng thực hiện")
        )).orElseThrow();
        preview = service.createResponse(callback(
                callbackData(collaboratorPrompt, "👤 Không - Tôi làm một mình")
        )).orElseThrow();
        assertThat(preview.getText()).contains("Người cùng thực hiện: Không có");

        SendMessage contentPrompt = service.createResponse(callback(
                callbackData(preview, "📝 Nội dung")
        )).orElseThrow();
        assertThat(contentPrompt.getText()).contains("Nội dung báo cáo");
        assertThat(service.createResponse(message("   ")).orElseThrow().getText()).contains("Nội dung");
        assertThat(service.createResponse(message("N".repeat(3001))).orElseThrow().getText()).contains("3000");
        preview = service.createResponse(message("Nội dung mới")).orElseThrow();
        assertThat(preview.getText())
                .contains("Tiêu đề: Tiêu đề mới")
                .contains("Người cùng thực hiện: Không có")
                .contains("Nội dung:\nNội dung mới");
        assertThat(buttons(preview)).extracting(InlineKeyboardButton::getText)
                .contains("✏️ Tiêu đề", "👥 Người cùng thực hiện", "📝 Nội dung", "✅ Xác nhận", "❌ Hủy");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldCancelByCommandAndButtonWithoutSaving() {
        service.createResponse(message("/report"));
        SendMessage commandCancelled = service.createResponse(message("/cancel")).orElseThrow();
        assertThat(commandCancelled.getText()).contains("Đã hủy");
        assertThat(service.createResponse(message("Không được lưu")).orElseThrow().getText())
                .contains("/report");

        SendMessage preview = reachPreview("Hủy", "Không có", "Nội dung nháp");
        String confirmCallback = callbackData(preview, "✅ Xác nhận");
        SendMessage buttonCancelled = service.createResponse(callback(
                callbackData(preview, "❌ Hủy")
        )).orElseThrow();

        assertThat(buttonCancelled.getText()).contains("Đã hủy");
        assertThat(service.createResponse(callback(confirmCallback)).orElseThrow().getText())
                .contains("hết hiệu lực");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldExpirePreviewAtThirtyMinutesBeforeConfirmation() {
        SendMessage preview = reachPreview("Hết hạn", "Không có", "Nội dung nháp");
        String confirmCallback = callbackData(preview, "✅ Xác nhận");
        clock.advance(Duration.ofMinutes(30));

        SendMessage response = service.createResponse(callback(confirmCallback)).orElseThrow();

        assertThat(response.getText()).contains("hết hiệu lực");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldExplainExpiryWhenTextArrivesAfterThirtyMinutes() {
        service.createResponse(message("/report"));
        service.createResponse(message("Tiêu đề đang nhập"));
        clock.advance(Duration.ofMinutes(30));

        SendMessage response = service.createResponse(message("Cộng sự quá muộn")).orElseThrow();

        assertThat(response.getText()).contains("Phiên nhập báo cáo đã hết hạn");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldSaveOnlyOnceWhenConfirmCallbacksRace() throws Exception {
        String storedContent = """
                Tiêu đề: Xác nhận đồng thời
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Không có
                Nội dung:
                Chỉ lưu một lần""";
        SendMessage preview = reachPreview(
                "Xác nhận đồng thời", "Không có", "Chỉ lưu một lần"
        );
        String confirmCallback = callbackData(preview, "✅ Xác nhận");
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(storedContent), anyString(), any(Instant.class)))
                .thenAnswer(invocation -> {
                    saveStarted.countDown();
                    if (!releaseSave.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release report save");
                    }
                    return DailyReportSubmissionStatus.SAVED;
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<SendMessage> first = executor.submit(() -> service.createResponse(
                    callback(confirmCallback)
            ).orElseThrow());
            assertThat(saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SendMessage> second = executor.submit(() -> service.createResponse(
                    callback(confirmCallback)
            ).orElseThrow());
            releaseSave.countDown();

            assertThat(List.of(
                    first.get(5, TimeUnit.SECONDS).getText(),
                    second.get(5, TimeUnit.SECONDS).getText()
            )).anySatisfy(text -> assertThat(text).contains("Đã lưu báo cáo"))
                    .anySatisfy(text -> assertThat(text).contains("hết hiệu lực"));
        verify(reportService, times(1)).submitToday(12345L, 12345L, storedContent, "Không có", clock.instant());
        } finally {
            releaseSave.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectOldPreviewAfterEditAndConfirmOnlyLatestPreview() {
        SendMessage preview = reachPreview("Tiêu đề cũ", "Cộng sự", "Nội dung");
        String oldConfirm = callbackData(preview, "✅ Xác nhận");
        service.createResponse(callback(callbackData(preview, "✏️ Tiêu đề")));
        SendMessage latestPreview = service.createResponse(message("Tiêu đề mới")).orElseThrow();
        String storedContent = """
                Tiêu đề: Tiêu đề mới
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Cộng sự
                Nội dung:
                Nội dung""";
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(storedContent), anyString(), any(Instant.class)))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        assertThat(service.createResponse(callback(oldConfirm)).orElseThrow().getText())
                .contains("hết hiệu lực");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));

        service.createResponse(callback(callbackData(latestPreview, "✅ Xác nhận")));
        verify(reportService).submitToday(12345L, 12345L, storedContent, "Cộng sự", clock.instant());
    }

    @Test
    void shouldIsolateDraftsByChatAndSender() {
        SendMessage first = reachPreview(
                "Tiêu đề A", "Cộng sự A", "Nội dung A", 1001L, 12345L, "Người A"
        );
        SendMessage second = reachPreview(
                "Tiêu đề B", "Cộng sự B", "Nội dung B", 1001L, 22222L, "Người B"
        );
        SendMessage third = reachPreview(
                "Tiêu đề C", "Cộng sự C", "Nội dung C", 2002L, 12345L, "Người A"
        );
        String firstConfirm = callbackData(first, "✅ Xác nhận");

        assertThat(service.createResponse(callback(firstConfirm, 1001L, 22222L)).orElseThrow().getText())
                .contains("hết hiệu lực");
        assertThat(service.createResponse(callback(firstConfirm, 2002L, 12345L)).orElseThrow().getText())
                .contains("hết hiệu lực");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));

        service.createResponse(callback(callbackData(second, "✏️ Tiêu đề"), 1001L, 22222L));
        SendMessage updatedSecond = service.createResponse(message(
                "Tiêu đề B mới", 1001L, 22222L
        )).orElseThrow();
        assertThat(updatedSecond.getText())
                .contains("Tiêu đề: Tiêu đề B mới")
                .contains("Nội dung:\nNội dung B")
                .doesNotContain("Tiêu đề A", "Tiêu đề C");
        assertThat(third.getText()).contains("Tiêu đề: Tiêu đề C");

        String firstContent = """
                Tiêu đề: Tiêu đề A
                Người thực hiện: Người A
                Người cùng thực hiện: Cộng sự A
                Nội dung:
                Nội dung A""";
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(firstContent), anyString(), any(Instant.class)))
                .thenReturn(DailyReportSubmissionStatus.SAVED);
        service.createResponse(callback(firstConfirm, 1001L, 12345L));
        verify(reportService).submitToday(12345L, 12345L, firstContent, "Cộng sự A", clock.instant());
    }

    @Test
    void shouldIgnoreOrdinaryTextOutsideWizard() {
        SendMessage response = service.createResponse(message("Nội dung không có phiên")).orElseThrow();

        assertThat(response.getText()).contains("/report");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldNotLogDraftFieldsWhenSaveFails() {
        String title = "PRIVATE_TITLE_7f9a";
        String collaborators = "PRIVATE_COLLABORATORS_8b2c";
        String content = "PRIVATE_CONTENT_4d1e";
        String storedContent = """
                Tiêu đề: PRIVATE_TITLE_7f9a
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: PRIVATE_COLLABORATORS_8b2c
                Nội dung:
                PRIVATE_CONTENT_4d1e""";
        SendMessage preview = reachPreview(title, collaborators, content);
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(storedContent), anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException(storedContent));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(TelegramCommandService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        SendMessage response;
        try {
            response = service.createResponse(callback(
                    callbackData(preview, "✅ Xác nhận")
            )).orElseThrow();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.getText()).contains("Không thể lưu báo cáo");
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(title, collaborators, content, storedContent);
            if (event.getThrowableProxy() != null) {
                assertThat(event.getThrowableProxy().getMessage())
                        .doesNotContain(title, collaborators, content, storedContent);
            }
        });
    }

    @Test
    void shouldExplainInactiveSubmissionWithoutSaving() {
        String storedContent = """
                Tiêu đề: Inactive
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Không có
                Nội dung:
                Should not be stored""";
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(storedContent), anyString(), any(Instant.class)))
                .thenReturn(DailyReportSubmissionStatus.USER_INACTIVE);
        when(userProfileService.findByTelegramUserId(12345L))
                .thenReturn(Optional.of(user(12345L, "Nguyễn Văn A")));

        service.createResponse(message("/report"));
        service.createResponse(message("Inactive"));
        service.createResponse(message("Không có"));
        SendMessage preview = service.createResponse(message("Should not be stored")).orElseThrow();
        String confirmCallback = callbackData(preview, "✅ Xác nhận");
        SendMessage response = service.createResponse(callback(confirmCallback)).orElseThrow();

        assertThat(response.getText()).contains("không thể gửi báo cáo mới");
        verify(reportService).submitToday(12345L, 12345L, storedContent, "Không có", clock.instant());
    }

    @Test
    void shouldRejectAStalePreviewAfterTelegramIdentityRemap() {
        String storedContent = """
                Tiêu đề: Stale owner
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Không có
                Nội dung:
                Should not move owners""";
        when(reportService.submitToday(eq(12345L), eq(12345L), eq(storedContent), anyString(), any(Instant.class)))
                .thenReturn(DailyReportSubmissionStatus.IDENTITY_CHANGED);

        SendMessage preview = reachPreview("Stale owner", "Không có", "Should not move owners");
        SendMessage response = service.createResponse(callback(
                callbackData(preview, "✅ Xác nhận")
        )).orElseThrow();

        assertThat(response.getText()).contains("Danh tính Telegram đã thay đổi").contains("/report");
        verify(reportService).submitToday(12345L, 12345L, storedContent, "Không có", clock.instant());
    }

    @Test
    void shouldShowTodayStatusWithoutSavingReport() {
        DailyReport report = report(LocalDate.of(2026, 6, 17), LocalDateTime.of(2026, 6, 17, 8, 30), "Done");
        when(reportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of(report));

        SendMessage response = service.createResponse(message("/status")).orElseThrow();

        assertThat(response.getText()).contains("2026-06-17").contains("Done");
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldNotExposeRemovedLastCommand() {
        SendMessage response = service.createResponse(message("/help")).orElseThrow();

        assertThat(service.createBotCommandMenu()).extracting(command -> command.getCommand())
                .doesNotContain("last");
        assertThat(response.getText()).doesNotContain("/last");
        assertThat(buttons(response)).extracting(InlineKeyboardButton::getCallbackData)
                .doesNotContain("command:/last");
        verifyNoInteractions(reportService);
    }

    @Test
    void shouldShowFiveRecentReportsWithoutSavingReport() {
        DailyReport report = report(LocalDate.of(2026, 6, 16), LocalDateTime.of(2026, 6, 16, 18, 0), "Recent");
        when(reportService.findRecentForTelegramUser(12345L, 5)).thenReturn(List.of(report));

        SendMessage response = service.createResponse(message("/myreports")).orElseThrow();

        assertThat(response.getText()).contains("Recent");
        verify(reportService).findRecentForTelegramUser(12345L, 5);
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldParseReportDateAndReadOnlyThatDate() {
        LocalDate reportDate = LocalDate.of(2026, 6, 15);
        DailyReport report = report(reportDate, LocalDateTime.of(2026, 6, 15, 18, 0), "Dated");
        when(reportService.findForTelegramUserOnDate(12345L, reportDate)).thenReturn(List.of(report));

        SendMessage response = service.createResponse(message("/reports 2026-06-15")).orElseThrow();

        assertThat(response.getText()).contains("2026-06-15").contains("Dated");
        verify(reportService).findForTelegramUserOnDate(12345L, reportDate);
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void shouldRejectInvalidReportDateWithoutCallingReportService() {
        SendMessage response = service.createResponse(message("/reports 15-06-2026")).orElseThrow();

        assertThat(response.getText()).contains("YYYY-MM-DD");
        verify(reportService, never()).findForTelegramUserOnDate(anyLong(), any(LocalDate.class));
        verify(reportService, never()).submitToday(anyLong(), anyLong(), anyString(), anyString(), any(Instant.class));
    }

    private DailyReport report(LocalDate reportDate, LocalDateTime createdAt, String content) {
        DailyReport report = new DailyReport();
        report.setReportDate(reportDate);
        report.setCreatedAt(createdAt);
        report.setContent(content);
        return report;
    }

    private ManagementReadResult noManagementData() {
        return new ManagementReadResult(
                IdentityAdminStatus.NO_DATA,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private User managementUser(int index) {
        User user = user(index, 90_000L + index, "Employee " + index);
        user.setEmployeeCode("EMP-%03d".formatted(index));
        user.setDepartmentName("Engineering");
        user.setUnitName("Core");
        user.setPhoneNumber("SECRET_PHONE");
        user.setUsername("private_username");
        user.setChatId(987_654_321L);
        user.setManager(index == 1);
        user.setAdmin(index == 2);
        return user;
    }

    private DailyReport managementReport(long id, User owner, int index) {
        DailyReport report = new DailyReport();
        report.setId(id);
        report.setUser(owner);
        report.setReportDate(LocalDate.of(2026, 7, 2));
        report.setCreatedAt(LocalDateTime.of(2026, 7, 2, 8, index));
        report.setDepartment("Engineering snapshot");
        report.setUnit("Core snapshot");
        report.setContent("report-preview-" + index);
        return report;
    }

    private IdentityAuditEvent managementAuditEvent(User target, int index) {
        return new IdentityAuditEvent(
                IdentityAdminAction.UPDATE_PROFILE,
                "user:1",
                target,
                null,
                "audit-reason-%02d".formatted(index),
                "SECRET_BEFORE_STATE private report body contentSha256",
                "SECRET_AFTER_STATE",
                LocalDateTime.of(2026, 7, 2, 9, index)
        );
    }

    private User authorizedUser(boolean admin, boolean manager, String department, String unit) {
        User user = user(12345L, "Manager");
        user.setAdmin(admin);
        user.setManager(manager);
        user.setDepartmentName(department);
        user.setUnitName(unit);
        return user;
    }

    private TeamReportSummary teamSummary(TeamScope scope) {
        TeamMemberReportStatus member = new TeamMemberReportStatus(
                501L,
                99999L,
                "SECRET-EMP",
                "An",
                "Private first name",
                "private_username",
                scope.departmentName(),
                scope.unitName(),
                "An",
                TeamReportStatus.DUE,
                null,
                List.of(new TeamReportEvidence(
                        701L,
                        LocalDate.of(2026, 6, 17),
                        "sensitive report content",
                        LocalDateTime.of(2026, 6, 17, 8, 0)
                ))
        );
        return new TeamReportSummary(
                scope,
                LocalDate.of(2026, 6, 17),
                clock.instant(),
                clock.getZone(),
                1,
                java.util.Map.of(TeamReportStatus.DUE, 1L),
                List.of(member)
        );
    }

    private SendMessage reachPreview(String title, String collaborators, String content) {
        return reachPreview(title, collaborators, content, 1001L, 12345L, "Nguyễn Văn A");
    }

    private SendMessage reachPreview(
            String title,
            String collaborators,
            String content,
            long chatId,
            long telegramUserId,
            String performer
    ) {
        when(userProfileService.findByTelegramUserId(telegramUserId))
                .thenReturn(Optional.of(user(telegramUserId, performer)));
        service.createResponse(message("/report", chatId, telegramUserId));
        service.createResponse(message(title, chatId, telegramUserId));
        service.createResponse(message(collaborators, chatId, telegramUserId));
        return service.createResponse(message(content, chatId, telegramUserId)).orElseThrow();
    }

    private User user(long telegramUserId, String fullName) {
        return user(telegramUserId, telegramUserId, fullName);
    }

    private User user(long internalId, long telegramUserId, String fullName) {
        User user = new User();
        user.setId(internalId);
        user.setTelegramUserId(telegramUserId);
        user.setFullName(fullName);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private Message message(String text) {
        return message(text, 1001L, 12345L);
    }

    private Message message(String text, long chatId, long telegramUserId) {
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                mock(org.telegram.telegrambots.meta.api.objects.User.class);
        when(telegramUser.getId()).thenReturn(telegramUserId);
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getFrom()).thenReturn(telegramUser);
        when(message.getText()).thenReturn(text);
        when(message.isUserMessage()).thenReturn(true);
        return message;
    }

    private Message groupMessage(String text) {
        Message message = message(text);
        when(message.isUserMessage()).thenReturn(false);
        return message;
    }

    private Message messageWithoutSender(String text) {
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);
        when(message.getText()).thenReturn(text);
        when(message.isUserMessage()).thenReturn(true);
        return message;
    }

    private CallbackQuery callback(String data) {
        return callback(data, 1001L, 12345L);
    }

    private CallbackQuery callback(String data, long chatId, long telegramUserId) {
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getChatId()).thenReturn(chatId);
        when(callbackMessage.isUserMessage()).thenReturn(true);
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                mock(org.telegram.telegrambots.meta.api.objects.User.class);
        when(telegramUser.getId()).thenReturn(telegramUserId);
        CallbackQuery callback = mock(CallbackQuery.class);
        when(callback.getMessage()).thenReturn(callbackMessage);
        when(callback.getFrom()).thenReturn(telegramUser);
        when(callback.getData()).thenReturn(data);
        return callback;
    }

    private String callbackData(SendMessage response, String buttonText) {
        return buttons(response).stream()
                .filter(button -> buttonText.equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .getCallbackData();
    }

    private List<InlineKeyboardButton> buttons(SendMessage response) {
        assertThat(response.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
        return ((InlineKeyboardMarkup) response.getReplyMarkup()).getKeyboard().stream()
                .flatMap(List::stream)
                .toList();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
