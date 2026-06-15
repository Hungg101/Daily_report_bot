package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramCommandServiceTest {

    private DailyReportService dailyReportService;
    private TelegramCommandService service;

    @BeforeEach
    void setUp() {
        TelegramBotProperties properties = new TelegramBotProperties();
        properties.setMiniAppUrl("https://example.com/miniapp/");

        dailyReportService = mock(DailyReportService.class);
        service = new TelegramCommandService(dailyReportService, properties, new ObjectMapper());
    }

    @Test
    void shouldReplyStartMessage() {
        SendMessage response = service.createResponse(createMessage("/start"));

        assertThat(response.getText()).isEqualTo("Xin chào! Đây là bot báo cáo công việc hằng ngày.");
    }

    @Test
    void shouldReplyHelpMessage() {
        SendMessage response = service.createResponse(createMessage("/help"));

        assertThat(response.getText()).contains("/start", "/help", "/report", "/miniapp");
    }

    @Test
    void shouldStartReportFlow() {
        when(dailyReportService.hasSubmittedToday(12345L)).thenReturn(false);

        SendMessage response = service.createResponse(createMessage("/report"));

        assertThat(response.getText()).isEqualTo("Vui lòng nhập nội dung báo cáo hôm nay.");
    }

    @Test
    void shouldCreateMiniAppKeyboardButton() {
        SendMessage response = service.createResponse(createMessage("/miniapp"));

        assertThat(response.getText()).isEqualTo("Mini app báo cáo đã sẵn sàng. Bấm nút bên dưới khung chat để mở trong Telegram.");
        assertThat(response.getReplyMarkup()).isInstanceOf(ReplyKeyboardMarkup.class);

        ReplyKeyboardMarkup keyboardMarkup = (ReplyKeyboardMarkup) response.getReplyMarkup();
        KeyboardButton button = keyboardMarkup.getKeyboard().get(0).get(0);

        assertThat(button.getText()).isEqualTo("Mở mini app báo cáo");
        assertThat(button.getWebApp()).isNotNull();
        assertThat(button.getWebApp().getUrl()).isEqualTo("https://example.com/miniapp/");
    }

    @Test
    void shouldExplainHttpsRequirementWhenMiniAppUrlIsLocalhost() {
        TelegramBotProperties properties = new TelegramBotProperties();
        properties.setMiniAppUrl("http://localhost:8080/miniapp/");
        TelegramCommandService localService = new TelegramCommandService(
                dailyReportService,
                properties,
                new ObjectMapper()
        );

        SendMessage response = localService.createResponse(createMessage("/miniapp"));

        assertThat(response.getText()).isEqualTo("Mini app cần URL HTTPS để mở trong Telegram. Hãy set TELEGRAM_MINI_APP_URL tới public HTTPS URL trỏ về /miniapp/.");
        assertThat(response.getReplyMarkup()).isNull();
    }

    @Test
    void shouldSaveReportTextAfterReportCommand() {
        when(dailyReportService.hasSubmittedToday(12345L)).thenReturn(false);
        when(dailyReportService.submitToday(12345L, "Hôm nay tôi đã hoàn thành API."))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        service.createResponse(createMessage("/report"));
        SendMessage response = service.createResponse(createMessage("Hôm nay tôi đã hoàn thành API."));

        assertThat(response.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
        verify(dailyReportService).submitToday(12345L, "Hôm nay tôi đã hoàn thành API.");
    }

    @Test
    void shouldSaveReportFromMiniAppData() {
        when(dailyReportService.submitToday(12345L, "Hoàn thành giao diện mini app."))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        SendMessage response = service.createResponse(createWebAppMessage(
                "{\"type\":\"daily_report\",\"content\":\"Hoàn thành giao diện mini app.\"}"
        ));

        assertThat(response.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
        verify(dailyReportService).submitToday(12345L, "Hoàn thành giao diện mini app.");
    }

    @Test
    void shouldRejectInvalidMiniAppData() {
        SendMessage response = service.createResponse(createWebAppMessage("{invalid-json"));

        assertThat(response.getText()).isEqualTo("Dữ liệu mini app không hợp lệ.");
    }

    @Test
    void shouldRejectBlankReportContentAndKeepWaiting() {
        when(dailyReportService.hasSubmittedToday(12345L)).thenReturn(false);
        when(dailyReportService.submitToday(12345L, "   "))
                .thenReturn(DailyReportSubmissionStatus.BLANK_CONTENT);
        when(dailyReportService.submitToday(12345L, "Nội dung hợp lệ"))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        service.createResponse(createMessage("/report"));
        SendMessage blankResponse = service.createResponse(createMessage("   "));
        SendMessage savedResponse = service.createResponse(createMessage("Nội dung hợp lệ"));

        assertThat(blankResponse.getText()).isEqualTo("Nội dung báo cáo không được để trống.");
        assertThat(savedResponse.getText()).isEqualTo("Đã lưu báo cáo hôm nay.");
    }

    @Test
    void shouldRejectReportWhenAlreadySubmittedToday() {
        when(dailyReportService.hasSubmittedToday(12345L)).thenReturn(true);

        SendMessage response = service.createResponse(createMessage("/report"));

        assertThat(response.getText()).isEqualTo("Bạn đã gửi báo cáo hôm nay rồi.");
    }

    @Test
    void shouldEchoNormalMessageOutsideReportFlow() {
        SendMessage response = service.createResponse(createMessage("Xin chào bot"));

        assertThat(response.getText()).isEqualTo("Bot đã nhận: Xin chào bot");
    }

    private Message createMessage(String text) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);

        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);
        when(message.getText()).thenReturn(text);
        when(message.getFrom()).thenReturn(user);
        return message;
    }

    private Message createWebAppMessage(String data) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);

        WebAppData webAppData = new WebAppData();
        webAppData.setData(data);

        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1001L);
        when(message.getFrom()).thenReturn(user);
        when(message.getWebAppData()).thenReturn(webAppData);
        return message;
    }
}
