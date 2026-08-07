package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTelegramCommandServiceTest {

    private DailyReportService reportService;
    private UserProfileService userProfileService;
    private IdentityAdministrationService identityAdministrationService;
    private GeminiAiService geminiAiService;
    private AiTelegramCommandService service;

    @BeforeEach
    void setUp() {
        reportService = mock(DailyReportService.class);
        userProfileService = mock(UserProfileService.class);
        TeamReportReadService teamReportReadService = mock(TeamReportReadService.class);
        identityAdministrationService = mock(IdentityAdministrationService.class);
        geminiAiService = mock(GeminiAiService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-17T01:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        TelegramBotProperties botProperties = new TelegramBotProperties();

        when(geminiAiService.isEnabled()).thenReturn(true);

        User activeUser = new User();
        activeUser.setId(1L);
        activeUser.setTelegramUserId(12345L);
        activeUser.setFullName("Test User");
        activeUser.setStatus(UserStatus.ACTIVE);
        when(userProfileService.findByTelegramUserId(12345L)).thenReturn(Optional.of(activeUser));

        service = new AiTelegramCommandService(
                reportService,
                userProfileService,
                teamReportReadService,
                identityAdministrationService,
                clock,
                botProperties,
                geminiAiService
        );
    }

    @Test
    void shouldInterceptUnsupportedCommand_andReturnAiResponse() {
        when(geminiAiService.getGuidanceResponse(eq(1001L), eq("/weather"), anyString()))
                .thenReturn("Tôi không biết thời tiết, nhưng bạn có thể gọi /report để nộp báo cáo!");

        Message message = message("/weather");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Tôi không biết thời tiết, nhưng bạn có thể gọi /report để nộp báo cáo!");
    }

    @Test
    void shouldInterceptReportRequired_andReturnAiResponse() {
        when(geminiAiService.getGuidanceResponse(eq(1001L), eq("Xin chào bot"), anyString()))
                .thenReturn("Xin chào! Tôi là trợ lý bot báo cáo. Gọi /report để nộp báo cáo nhé!");

        Message message = message("Xin chào bot");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Xin chào! Tôi là trợ lý bot báo cáo. Gọi /report để nộp báo cáo nhé!");
    }

    @Test
    void shouldPassThrough_helpCommand_unchanged() {
        Message message = message("/help");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).startsWith("Danh sách lệnh:");
        verify(geminiAiService, never()).getGuidanceResponse(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldPassThrough_startCommand_unchanged() {
        Message message = message("/start");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Xin chào! Đây là bot báo cáo công việc hằng ngày.");
        verify(geminiAiService, never()).getGuidanceResponse(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldPassThrough_statusCommand_unchanged() {
        Message message = message("/status");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).contains("Trạng thái báo cáo hôm nay");
        verify(geminiAiService, never()).getGuidanceResponse(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldFallbackToOriginal_whenAiReturnsNull() {
        when(geminiAiService.getGuidanceResponse(eq(1001L), eq("Xin chào bot"), anyString())).thenReturn(null);

        Message message = message("Xin chào bot");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
    }

    @Test
    void shouldFallbackToOriginal_whenAiThrowsException() {
        when(geminiAiService.getGuidanceResponse(eq(1001L), eq("/unknown"), anyString()))
                .thenThrow(new RuntimeException("Network error"));

        Message message = message("/unknown");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).startsWith("Lệnh không được hỗ trợ");
    }

    @Test
    void shouldFallbackToOriginal_whenAiIsDisabled() {
        when(geminiAiService.isEnabled()).thenReturn(false);

        Message message = message("Xin chào bot");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Vui lòng gửi /report trước khi nhập báo cáo mới.");
        verify(geminiAiService, never()).getGuidanceResponse(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldReturnCustomMessage_whenAiIsDisabled_andAiCommandUsed_inPrivateChat() {
        when(geminiAiService.isEnabled()).thenReturn(false);

        Message message = message("/ai câu hỏi");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Chức năng AI hiện chưa được kích hoạt. Vui lòng liên hệ quản trị viên để cấu hình GEMINI_API_KEY.");
        verify(geminiAiService, never()).answerUserQuery(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void shouldReturnCustomMessage_whenAiIsDisabled_andAiCommandUsed_inGroupChat() {
        when(geminiAiService.isEnabled()).thenReturn(false);

        Message message = message("/ai câu hỏi", -1001L, 12345L, false);
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText())
                .isEqualTo("Chức năng AI chỉ sử dụng được trong chat riêng với bot.");
        verify(geminiAiService, never()).answerUserQuery(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void shouldNotIntercept_reportSessionInput() {
        Message reportCmd = message("/report");
        service.createResponse(reportCmd);

        Message titleInput = message("Tiêu đề báo cáo");
        Optional<SendMessage> response = service.createResponse(titleInput);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).contains("Người cùng thực hiện");
        verify(geminiAiService, never()).getGuidanceResponse(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldInterceptActiveMode_andReturnAiResponse() {
        when(geminiAiService.answerUserQuery(eq(1001L), eq("tổng hợp báo cáo"), anyString(), any(User.class)))
                .thenReturn("Đây là báo cáo của bạn.");

        Message message = message("/ai tổng hợp báo cáo");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).isEqualTo("Đây là báo cáo của bạn.");
    }

    @Test
    void shouldFallback_whenActiveAiFails() {
        when(geminiAiService.answerUserQuery(eq(1001L), eq("tổng hợp báo cáo"), anyString(), any()))
                .thenThrow(new RuntimeException("Network error"));

        Message message = message("/ai tổng hợp báo cáo");
        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).isEqualTo("Xin lỗi, chức năng AI hiện không phản hồi. Vui lòng thử lại sau.");
    }

    @Test
    void shouldRejectAiInGroupChat() {
        Message message = message("/ai tổng hợp báo cáo", -1001L, 12345L, false);

        Optional<SendMessage> response = service.createResponse(message);

        assertThat(response).isPresent();
        assertThat(response.get().getText()).contains("chỉ sử dụng được trong chat riêng");
        verify(geminiAiService, never()).answerUserQuery(anyLong(), anyString(), anyString(), any());
        verify(geminiAiService, never()).getGuidanceResponse(anyLong(), anyString(), anyString());
    }

    @Test
    void shouldExposeAiCommandOnce() {
        assertThat(service.createBotCommandMenu())
                .filteredOn(command -> command.getCommand().equals("ai"))
                .hasSize(1);
    }

    @Test
    void shouldReturnEmpty_whenMessageIsNull() {
        Optional<SendMessage> response = service.createResponse((Message) null);
        assertThat(response).isEmpty();
    }

    private Message message(String text) {
        return message(text, 1001L, 12345L);
    }

    private Message message(String text, long chatId, long telegramUserId) {
        return message(text, chatId, telegramUserId, true);
    }

    private Message message(String text, long chatId, long telegramUserId, boolean privateChat) {
        org.telegram.telegrambots.meta.api.objects.User telegramUser =
                mock(org.telegram.telegrambots.meta.api.objects.User.class);
        when(telegramUser.getId()).thenReturn(telegramUserId);
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getFrom()).thenReturn(telegramUser);
        when(message.getText()).thenReturn(text);
        when(message.isUserMessage()).thenReturn(privateChat);
        return message;
    }
}
