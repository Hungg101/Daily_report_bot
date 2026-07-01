package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.Employee;
import com.example.dailyreportbot.entity.EmployeeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramCommandServiceTest {

    private DailyReportService dailyReportService;
    private TelegramUserMappingService telegramUserMappingService;
    private TelegramCommandService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        dailyReportService = mock(DailyReportService.class);
        telegramUserMappingService = mock(TelegramUserMappingService.class);
        clock = new MutableClock(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new TelegramCommandService(dailyReportService, telegramUserMappingService, clock);
    }

    @Test
    void shouldReplyStartMessage() {
        SendMessage response = service.createResponse(createMessage("/start")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Xin chào! Đây là bot báo cáo công việc hằng ngày.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
    }

    @Test
    void shouldCreateTelegramCommandMenu() {
        List<BotCommand> commands = service.createBotCommandMenu();

        assertThat(commands)
                .extracting(BotCommand::getCommand)
                .containsExactly("start", "help", "report", "status", "cancel", "last", "myreports", "reports", "link", "whoami", "miniapp");
        assertThat(commands)
                .extracting(BotCommand::getDescription)
                .containsExactly(
                        "Bắt đầu sử dụng bot",
                        "Xem các lệnh có thể dùng",
                        "Gửi báo cáo công việc hôm nay",
                        "Xem trạng thái báo cáo hôm nay",
                        "Hủy phiên nhập báo cáo hiện tại",
                        "Xem báo cáo gần nhất của bạn",
                        "Xem 5 báo cáo gần đây của bạn",
                        "Xem báo cáo của bạn theo ngày",
                        "Liên kết Telegram với mã nhân viên",
                        "Xem liên kết Telegram với nhân viên",
                        "Mini App đang tạm tắt"
                );
    }

    @Test
    void shouldReplyHelpMessageWithoutMiniApp() {
        SendMessage response = service.createResponse(createMessage("/help")).orElseThrow();

        assertThat(response.getText()).contains("/start", "/help", "/report");
        assertThat(response.getText()).contains("/status");
        assertThat(response.getText()).contains("/cancel", "/last", "/myreports");
        assertThat(response.getText()).contains("/reports YYYY-MM-DD");
        assertThat(response.getText()).contains("/link EMPLOYEE_CODE");
        assertThat(response.getText()).contains("/whoami");
        assertThat(response.getText()).doesNotContain("/miniapp");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/last", "command:/status", "command:/myreports", "command:/link", "command:/whoami", "command:/cancel", "command:/start");
    }

    @Test
    void shouldStartReportFlow() {
        SendMessage response = service.createResponse(createMessage("/report")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Vui lòng nhập nội dung báo cáo hôm nay.");
        assertKeyboardContainsCallbacks(response, "command:/cancel", "command:/help");
    }

    @Test
    void shouldExplainMiniAppIsDisabled() {
        SendMessage response = service.createResponse(createMessage("/miniapp")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Mini app đang tạm tắt. Hãy dùng /report để gửi báo cáo trong chat Telegram.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        assertThat(extractKeyboardButtons(response))
                .allSatisfy(button -> assertThat(button.getWebApp()).isNull());
    }

    @Test
    void shouldCancelActiveReportSession() {
        service.createResponse(createMessage("/report"));
        SendMessage cancelResponse = service.createResponse(createMessage("/cancel")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(cancelResponse.getText()).isEqualTo("Đã hủy phiên nhập báo cáo.");
        assertKeyboardContainsCallbacks(cancelResponse, "command:/report", "command:/help");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainThereIsNoReportSessionToCancel() {
        SendMessage response = service.createResponse(createMessage("/cancel")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Không có phiên nhập báo cáo nào đang mở.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldShowLatestReport() {
        DailyReport report = new DailyReport();
        report.setReportDate(LocalDate.of(2026, 6, 17));
        report.setCreatedAt(LocalDateTime.of(2026, 6, 17, 8, 30));
        report.setContent("Nội dung báo cáo mới nhất");
        when(dailyReportService.findLatestForTelegramUser(12345L)).thenReturn(Optional.of(report));

        SendMessage response = service.createResponse(createMessage("/last")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Báo cáo gần nhất:
                Ngày báo cáo: 2026-06-17
                Thời gian lưu: 2026-06-17 08:30
                Nội dung:
                Nội dung báo cáo mới nhất""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findLatestForTelegramUser(12345L);
    }

    @Test
    void shouldExplainWhenLatestReportDoesNotExist() {
        when(dailyReportService.findLatestForTelegramUser(12345L)).thenReturn(Optional.empty());

        SendMessage response = service.createResponse(createMessage("/last")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findLatestForTelegramUser(12345L);
    }

    @Test
    void shouldShowRecentReports() {
        DailyReport latestReport = new DailyReport();
        latestReport.setReportDate(LocalDate.of(2026, 6, 17));
        latestReport.setCreatedAt(LocalDateTime.of(2026, 6, 17, 9, 15));
        latestReport.setContent("Báo cáo mới nhất");

        DailyReport previousReport = new DailyReport();
        previousReport.setReportDate(LocalDate.of(2026, 6, 16));
        previousReport.setCreatedAt(LocalDateTime.of(2026, 6, 16, 18, 0));
        previousReport.setContent("Báo cáo hôm qua");

        when(dailyReportService.findRecentForTelegramUser(12345L, 5))
                .thenReturn(List.of(latestReport, previousReport));

        SendMessage response = service.createResponse(createMessage("/myreports")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                5 báo cáo gần đây của bạn:

                1. Ngày báo cáo: 2026-06-17
                Thời gian lưu: 2026-06-17 09:15
                Nội dung:
                Báo cáo mới nhất

                2. Ngày báo cáo: 2026-06-16
                Thời gian lưu: 2026-06-16 18:00
                Nội dung:
                Báo cáo hôm qua""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findRecentForTelegramUser(12345L, 5);
    }

    @Test
    void shouldShowTodayReportStatusWhenReportsExist() {
        DailyReport latestReport = new DailyReport();
        latestReport.setReportDate(LocalDate.of(2026, 6, 17));
        latestReport.setCreatedAt(LocalDateTime.of(2026, 6, 17, 18, 0));
        latestReport.setContent("Báo cáo mới nhất hôm nay");

        DailyReport previousReport = new DailyReport();
        previousReport.setReportDate(LocalDate.of(2026, 6, 17));
        previousReport.setCreatedAt(LocalDateTime.of(2026, 6, 17, 9, 15));
        previousReport.setContent("Báo cáo buổi sáng");

        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of(latestReport, previousReport));

        SendMessage response = service.createResponse(createMessage("/status")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Trạng thái báo cáo hôm nay (2026-06-17):
                Đã có 2 báo cáo.
                Lần mới nhất lưu lúc: 2026-06-17 18:00
                Nội dung mới nhất:
                Báo cáo mới nhất hôm nay""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldExplainTodayReportStatusWhenReportsDoNotExist() {
        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of());

        SendMessage response = service.createResponse(createMessage("/status")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Trạng thái báo cáo hôm nay (2026-06-17):
                Chưa có báo cáo nào. Gửi /report để nộp báo cáo hôm nay.""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldHandleTodayReportStatusFailure() {
        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenThrow(new RuntimeException("database unavailable"));

        SendMessage response = service.createResponse(createMessage("/status")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Không thể lấy trạng thái báo cáo hôm nay lúc này. Vui lòng thử lại sau.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldShowReportsByDate() {
        DailyReport firstReport = new DailyReport();
        firstReport.setReportDate(LocalDate.of(2026, 6, 17));
        firstReport.setCreatedAt(LocalDateTime.of(2026, 6, 17, 9, 15));
        firstReport.setContent("Báo cáo buổi sáng");

        DailyReport secondReport = new DailyReport();
        secondReport.setReportDate(LocalDate.of(2026, 6, 17));
        secondReport.setCreatedAt(LocalDateTime.of(2026, 6, 17, 18, 0));
        secondReport.setContent("Báo cáo cuối ngày");

        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of(secondReport, firstReport));

        SendMessage response = service.createResponse(createMessage("/reports 2026-06-17")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Báo cáo ngày 2026-06-17 của bạn:

                1. Ngày báo cáo: 2026-06-17
                Thời gian lưu: 2026-06-17 18:00
                Nội dung:
                Báo cáo cuối ngày

                2. Ngày báo cáo: 2026-06-17
                Thời gian lưu: 2026-06-17 09:15
                Nội dung:
                Báo cáo buổi sáng""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldExplainWhenReportsByDateDoNotExist() {
        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of());

        SendMessage response = service.createResponse(createMessage("/reports 2026-06-17")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn trong ngày 2026-06-17.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldExplainReportsByDateUsageWhenDateIsMissing() {
        SendMessage response = service.createResponse(createMessage("/reports")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Cú pháp: /reports YYYY-MM-DD
                Ví dụ: /reports 2026-06-17""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainWhenReportsByDateHasInvalidDate() {
        SendMessage response = service.createResponse(createMessage("/reports 17-06-2026")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Ngày không hợp lệ. Hãy dùng định dạng YYYY-MM-DD, ví dụ /reports 2026-06-17.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldLinkTelegramUserToEmployee() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "EMP001"))
                .thenReturn(TelegramUserMappingStatus.LINKED);

        SendMessage response = service.createResponse(createMessage("/link EMP001")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Đã liên kết tài khoản Telegram của bạn với mã nhân viên EMP001.
                Dùng /whoami để kiểm tra.""");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "EMP001");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainLinkUsageWhenEmployeeCodeIsMissing() {
        SendMessage response = service.createResponse(createMessage("/link")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Cú pháp: /link EMPLOYEE_CODE
                Ví dụ: /link EMP001""");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verifyNoInteractions(dailyReportService, telegramUserMappingService);
    }

    @Test
    void shouldExplainLinkUsageWhenThereAreTooManyArguments() {
        SendMessage response = service.createResponse(createMessage("/link EMP001 EXTRA")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Cú pháp: /link EMPLOYEE_CODE
                Ví dụ: /link EMP001""");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verifyNoInteractions(dailyReportService, telegramUserMappingService);
    }

    @Test
    void shouldExplainWhenLinkEmployeeDoesNotExist() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "UNKNOWN"))
                .thenReturn(TelegramUserMappingStatus.EMPLOYEE_NOT_FOUND);

        SendMessage response = service.createResponse(createMessage("/link UNKNOWN")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Không tìm thấy nhân viên với mã này. Hãy kiểm tra lại mã nhân viên.");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "UNKNOWN");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainWhenTelegramUserIsAlreadyLinked() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "EMP001"))
                .thenReturn(TelegramUserMappingStatus.TELEGRAM_USER_ALREADY_LINKED);

        SendMessage response = service.createResponse(createMessage("/link EMP001")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Tài khoản Telegram của bạn đã được liên kết với nhân viên. Dùng /whoami để kiểm tra.");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "EMP001");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainWhenEmployeeIsAlreadyLinked() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "EMP001"))
                .thenReturn(TelegramUserMappingStatus.EMPLOYEE_ALREADY_LINKED);

        SendMessage response = service.createResponse(createMessage("/link EMP001")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Mã nhân viên này đã được liên kết với tài khoản Telegram khác.");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "EMP001");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainWhenLinkEmployeeIsInactive() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "EMP001"))
                .thenReturn(TelegramUserMappingStatus.EMPLOYEE_INACTIVE);

        SendMessage response = service.createResponse(createMessage("/link EMP001")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Nhân viên này đang ngừng hoạt động, không thể liên kết.");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "EMP001");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldHandleLinkFailure() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "EMP001"))
                .thenThrow(new RuntimeException("database unavailable"));

        SendMessage response = service.createResponse(createMessage("/link EMP001")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Không thể liên kết tài khoản lúc này. Vui lòng thử lại sau.");
        assertKeyboardContainsCallbacks(response, "command:/whoami", "command:/help", "command:/report");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "EMP001");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldClearReportSessionWhenLinkingEmployee() {
        when(telegramUserMappingService.linkTelegramUserToEmployee(12345L, "EMP001"))
                .thenReturn(TelegramUserMappingStatus.LINKED);

        service.createResponse(createMessage("/report"));
        SendMessage linkResponse = service.createResponse(createMessage("/link EMP001")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(linkResponse.getText()).contains("Đã liên kết tài khoản Telegram của bạn");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(telegramUserMappingService).linkTelegramUserToEmployee(12345L, "EMP001");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldShowMappedEmployee() {
        Employee employee = new Employee();
        employee.setCode("EMP001");
        employee.setFullName("Nguyễn Văn A");
        employee.setStatus(EmployeeStatus.ACTIVE);
        when(telegramUserMappingService.findMappedEmployee(12345L)).thenReturn(Optional.of(employee));

        SendMessage response = service.createResponse(createMessage("/whoami")).orElseThrow();

        assertThat(response.getText()).isEqualTo("""
                Thông tin tài khoản:
                Telegram user ID: 12345
                Mã nhân viên: EMP001
                Họ tên: Nguyễn Văn A
                Trạng thái: Đang hoạt động""");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(telegramUserMappingService).findMappedEmployee(12345L);
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExplainWhenTelegramUserIsNotMappedToEmployee() {
        when(telegramUserMappingService.findMappedEmployee(12345L)).thenReturn(Optional.empty());

        SendMessage response = service.createResponse(createMessage("/whoami")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Tài khoản Telegram của bạn chưa được liên kết với nhân viên nào.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(telegramUserMappingService).findMappedEmployee(12345L);
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldHandleMappedEmployeeLookupFailure() {
        when(telegramUserMappingService.findMappedEmployee(12345L)).thenThrow(new RuntimeException("database unavailable"));

        SendMessage response = service.createResponse(createMessage("/whoami")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Không thể lấy thông tin liên kết lúc này. Vui lòng thử lại sau.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(telegramUserMappingService).findMappedEmployee(12345L);
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldClearReportSessionWhenShowingMappedEmployee() {
        when(telegramUserMappingService.findMappedEmployee(12345L)).thenReturn(Optional.empty());

        service.createResponse(createMessage("/report"));
        SendMessage whoamiResponse = service.createResponse(createMessage("/whoami")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(whoamiResponse.getText()).isEqualTo("Tài khoản Telegram của bạn chưa được liên kết với nhân viên nào.");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(telegramUserMappingService).findMappedEmployee(12345L);
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldTruncateLongRecentReportContent() {
        DailyReport report = new DailyReport();
        report.setReportDate(LocalDate.of(2026, 6, 17));
        report.setContent("a".repeat(260));
        when(dailyReportService.findRecentForTelegramUser(12345L, 5)).thenReturn(List.of(report));

        SendMessage response = service.createResponse(createMessage("/myreports")).orElseThrow();

        assertThat(response.getText()).contains("a".repeat(240) + "...");
        assertThat(response.getText()).doesNotContain("a".repeat(241));
        verify(dailyReportService).findRecentForTelegramUser(12345L, 5);
    }

    @Test
    void shouldExplainWhenRecentReportsDoNotExist() {
        when(dailyReportService.findRecentForTelegramUser(12345L, 5)).thenReturn(List.of());

        SendMessage response = service.createResponse(createMessage("/myreports")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findRecentForTelegramUser(12345L, 5);
    }

    @Test
    void shouldClearReportSessionWhenShowingRecentReports() {
        when(dailyReportService.findRecentForTelegramUser(12345L, 5)).thenReturn(List.of());

        service.createResponse(createMessage("/report"));
        SendMessage recentReportsResponse = service.createResponse(createMessage("/myreports")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(recentReportsResponse.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn.");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(dailyReportService).findRecentForTelegramUser(12345L, 5);
    }

    @Test
    void shouldClearReportSessionWhenShowingReportsByDate() {
        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of());

        service.createResponse(createMessage("/report"));
        SendMessage reportsByDateResponse = service.createResponse(createMessage("/reports 2026-06-17")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(reportsByDateResponse.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn trong ngày 2026-06-17.");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldClearReportSessionWhenShowingTodayReportStatus() {
        when(dailyReportService.findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17)))
                .thenReturn(List.of());

        service.createResponse(createMessage("/report"));
        SendMessage statusResponse = service.createResponse(createMessage("/status")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(statusResponse.getText()).isEqualTo("""
                Trạng thái báo cáo hôm nay (2026-06-17):
                Chưa có báo cáo nào. Gửi /report để nộp báo cáo hôm nay.""");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(dailyReportService).findForTelegramUserOnDate(12345L, LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldClearReportSessionWhenShowingLatestReport() {
        when(dailyReportService.findLatestForTelegramUser(12345L)).thenReturn(Optional.empty());

        service.createResponse(createMessage("/report"));
        SendMessage latestResponse = service.createResponse(createMessage("/last")).orElseThrow();
        SendMessage textResponse = service.createResponse(createMessage("Không được lưu")).orElseThrow();

        assertThat(latestResponse.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn.");
        assertThat(textResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(dailyReportService).findLatestForTelegramUser(12345L);
    }

    @Test
    void shouldSaveReportTextAfterReportCommand() {
        when(dailyReportService.submitToday(12345L, "Hôm nay tôi đã hoàn thành API."))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        service.createResponse(createMessage("/report"));
        SendMessage response = service.createResponse(createMessage("Hôm nay tôi đã hoàn thành API.")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/last", "command:/myreports", "command:/help");
        verify(dailyReportService).submitToday(12345L, "Hôm nay tôi đã hoàn thành API.");
    }

    @Test
    void shouldRejectBlankReportContent() {
        when(dailyReportService.submitToday(12345L, "   "))
                .thenReturn(DailyReportSubmissionStatus.BLANK_CONTENT);

        service.createResponse(createMessage("/report"));
        SendMessage response = service.createResponse(createMessage("   ")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Nội dung báo cáo không được để trống.");
        assertKeyboardContainsCallbacks(response, "command:/cancel", "command:/help");
        verify(dailyReportService).submitToday(12345L, "   ");
    }

    @Test
    void shouldKeepReportSessionOpenAfterBlankContent() {
        when(dailyReportService.submitToday(12345L, "   "))
                .thenReturn(DailyReportSubmissionStatus.BLANK_CONTENT);
        when(dailyReportService.submitToday(12345L, "Nội dung hợp lệ"))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        service.createResponse(createMessage("/report"));
        SendMessage blankResponse = service.createResponse(createMessage("   ")).orElseThrow();
        SendMessage savedResponse = service.createResponse(createMessage("Nội dung hợp lệ")).orElseThrow();

        assertThat(blankResponse.getText()).isEqualTo("Nội dung báo cáo không được để trống.");
        assertThat(savedResponse.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
        verify(dailyReportService).submitToday(12345L, "   ");
        verify(dailyReportService).submitToday(12345L, "Nội dung hợp lệ");
    }

    @Test
    void shouldRequireReportCommandBeforeSavingText() {
        SendMessage response = service.createResponse(createMessage("Xin chào bot")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldCreateResponseFromInlineKeyboardCallback() {
        SendMessage response = service.createResponse(createCallbackQuery("/report")).orElseThrow();

        assertThat(response.getChatId()).isEqualTo("1001");
        assertThat(response.getText()).isEqualTo("Vui lòng nhập nội dung báo cáo hôm nay.");
        assertKeyboardContainsCallbacks(response, "command:/cancel", "command:/help");
    }

    @Test
    void shouldCreateRecentReportsResponseFromInlineKeyboardCallback() {
        when(dailyReportService.findRecentForTelegramUser(12345L, 5)).thenReturn(List.of());

        SendMessage response = service.createResponse(createCallbackQuery("/myreports")).orElseThrow();

        assertThat(response.getChatId()).isEqualTo("1001");
        assertThat(response.getText()).isEqualTo("Chưa tìm thấy báo cáo nào của bạn.");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/help");
        verify(dailyReportService).findRecentForTelegramUser(12345L, 5);
    }

    @Test
    void shouldNotCreateCallbackResponseForUnsupportedCallbackData() {
        assertThat(service.createResponse(createCallbackQuery("unknown"))).isEmpty();
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldRequireReportCommandForEachNewReport() {
        when(dailyReportService.submitToday(12345L, "Báo cáo lần 1"))
                .thenReturn(DailyReportSubmissionStatus.SAVED);
        when(dailyReportService.submitToday(12345L, "Báo cáo lần 2"))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        service.createResponse(createMessage("/report"));
        SendMessage firstResponse = service.createResponse(createMessage("Báo cáo lần 1")).orElseThrow();
        SendMessage blockedResponse = service.createResponse(createMessage("Báo cáo lần 2")).orElseThrow();
        service.createResponse(createMessage("/report"));
        SendMessage secondResponse = service.createResponse(createMessage("Báo cáo lần 2")).orElseThrow();

        assertThat(firstResponse.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
        assertThat(blockedResponse.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        assertThat(secondResponse.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
        verify(dailyReportService).submitToday(12345L, "Báo cáo lần 1");
        verify(dailyReportService, times(1)).submitToday(12345L, "Báo cáo lần 2");
    }

    @Test
    void shouldNotCreateResponseWithoutChatId() {
        assertThat(service.createResponse(createMessage("/start", null))).isEmpty();
        verifyNoInteractions(dailyReportService);
    }

    @Test
    void shouldExpirePendingReportSession() {
        service.createResponse(createMessage("/report"));
        clock.advance(Duration.ofMinutes(31));

        SendMessage response = service.createResponse(createMessage("Báo cáo sau khi hết hạn")).orElseThrow();

        assertThat(response.getText()).isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verifyNoInteractions(dailyReportService);
    }

    private Message createMessage(String text) {
        return createMessage(text, 1001L);
    }

    private Message createMessage(String text, Long chatId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);

        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getText()).thenReturn(text);
        when(message.getFrom()).thenReturn(user);
        return message;
    }

    private CallbackQuery createCallbackQuery(String command) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);

        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);

        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(callbackQuery.getFrom()).thenReturn(user);
        when(callbackQuery.getMessage()).thenReturn(message);
        when(callbackQuery.getData()).thenReturn(command.startsWith("/") ? "command:" + command : command);
        return callbackQuery;
    }

    private void assertKeyboardContainsCallbacks(SendMessage response, String... callbackData) {
        assertThat(extractKeyboardButtons(response))
                .extracting(InlineKeyboardButton::getCallbackData)
                .contains(callbackData);
    }

    private List<InlineKeyboardButton> extractKeyboardButtons(SendMessage response) {
        assertThat(response.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) response.getReplyMarkup();
        return markup.getKeyboard().stream()
                .flatMap(Collection::stream)
                .toList();
    }

    private static class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
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
