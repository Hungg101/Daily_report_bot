package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
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
    private TelegramCommandService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        dailyReportService = mock(DailyReportService.class);
        clock = new MutableClock(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        service = new TelegramCommandService(dailyReportService, clock);
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
                .containsExactly("start", "help", "report", "cancel", "last", "miniapp");
        assertThat(commands)
                .extracting(BotCommand::getDescription)
                .containsExactly(
                        "Bắt đầu sử dụng bot",
                        "Xem các lệnh có thể dùng",
                        "Gửi báo cáo công việc hôm nay",
                        "Hủy phiên nhập báo cáo hiện tại",
                        "Xem báo cáo gần nhất của bạn",
                        "Mini App đang tạm tắt"
                );
    }

    @Test
    void shouldReplyHelpMessageWithoutMiniApp() {
        SendMessage response = service.createResponse(createMessage("/help")).orElseThrow();

        assertThat(response.getText()).contains("/start", "/help", "/report");
        assertThat(response.getText()).contains("/cancel", "/last");
        assertThat(response.getText()).doesNotContain("/miniapp");
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/last", "command:/cancel", "command:/start");
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
        assertKeyboardContainsCallbacks(response, "command:/report", "command:/last", "command:/help");
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
