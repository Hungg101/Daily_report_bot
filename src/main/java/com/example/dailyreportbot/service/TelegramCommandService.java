package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppData;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramCommandService {

    private static final String START_MESSAGE = "Xin chào! Đây là bot báo cáo công việc hằng ngày.";
    private static final String HELP_MESSAGE = "Danh sách lệnh:\n/start\n/help\n/report\n/miniapp";
    private static final String REPORT_MESSAGE = "Vui lòng nhập nội dung báo cáo hôm nay.";
    private static final String REPORT_SAVED_MESSAGE = "Đã lưu báo cáo hôm nay.";
    private static final String REPORT_ALREADY_SUBMITTED_MESSAGE = "Bạn đã gửi báo cáo hôm nay rồi.";
    private static final String BLANK_REPORT_MESSAGE = "Nội dung báo cáo không được để trống.";
    private static final String USER_NOT_FOUND_MESSAGE = "Không tìm thấy người dùng Telegram.";
    private static final String MINI_APP_MESSAGE = "Mini app báo cáo đã sẵn sàng. Bấm nút bên dưới khung chat để mở trong Telegram.";
    private static final String MINI_APP_HTTPS_REQUIRED_MESSAGE = "Mini app cần URL HTTPS để mở trong Telegram. Hãy set TELEGRAM_MINI_APP_URL tới public HTTPS URL trỏ về /miniapp/.";
    private static final String MINI_APP_BUTTON_TEXT = "Mở mini app báo cáo";
    private static final String INVALID_WEB_APP_DATA_MESSAGE = "Dữ liệu mini app không hợp lệ.";
    private static final String DEFAULT_MINI_APP_URL = "http://localhost:8080/miniapp/";

    private final DailyReportService dailyReportService;
    private final TelegramBotProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<Long> waitingForReportContent = ConcurrentHashMap.newKeySet();

    public TelegramCommandService(
            DailyReportService dailyReportService,
            TelegramBotProperties properties,
            ObjectMapper objectMapper
    ) {
        this.dailyReportService = dailyReportService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public SendMessage createResponse(Message message) {
        SendMessage response = new SendMessage();
        response.setChatId(message.getChatId().toString());

        if (message.getWebAppData() != null) {
            response.setText(handleWebAppData(message));
            return response;
        }

        String command = normalizeCommand(message.getText());
        response.setText(resolveResponseText(message, command));
        if ("/miniapp".equals(command) && hasHttpsMiniAppUrl()) {
            response.setReplyMarkup(createMiniAppKeyboard());
        }

        return response;
    }

    private String resolveResponseText(Message message, String command) {
        String text = message.getText();
        Long telegramUserId = resolveTelegramUserId(message);

        return switch (command) {
            case "/start" -> START_MESSAGE;
            case "/help" -> HELP_MESSAGE;
            case "/report" -> startReportFlow(telegramUserId);
            case "/miniapp" -> hasHttpsMiniAppUrl() ? MINI_APP_MESSAGE : MINI_APP_HTTPS_REQUIRED_MESSAGE;
            default -> handleNormalText(telegramUserId, text);
        };
    }

    private String handleWebAppData(Message message) {
        Long telegramUserId = resolveTelegramUserId(message);
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        String content = extractReportContent(message.getWebAppData());
        if (content == null) {
            return INVALID_WEB_APP_DATA_MESSAGE;
        }

        return submitReport(telegramUserId, content);
    }

    private String extractReportContent(WebAppData webAppData) {
        if (webAppData == null || !StringUtils.hasText(webAppData.getData())) {
            return "";
        }

        try {
            JsonNode contentNode = objectMapper.readTree(webAppData.getData()).get("content");
            if (contentNode == null || contentNode.isNull()) {
                return null;
            }
            return contentNode.asText();
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private ReplyKeyboardMarkup createMiniAppKeyboard() {
        KeyboardButton button = new KeyboardButton();
        button.setText(MINI_APP_BUTTON_TEXT);

        WebAppInfo webAppInfo = new WebAppInfo();
        webAppInfo.setUrl(resolveMiniAppUrl());
        button.setWebApp(webAppInfo);

        KeyboardRow keyboardRow = new KeyboardRow();
        keyboardRow.add(button);

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setKeyboard(List.of(keyboardRow));
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);
        return keyboardMarkup;
    }

    private boolean hasHttpsMiniAppUrl() {
        return resolveMiniAppUrl().startsWith("https://");
    }

    private String resolveMiniAppUrl() {
        if (properties != null && StringUtils.hasText(properties.getMiniAppUrl())) {
            return properties.getMiniAppUrl();
        }

        return DEFAULT_MINI_APP_URL;
    }

    private String startReportFlow(Long telegramUserId) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        if (dailyReportService.hasSubmittedToday(telegramUserId)) {
            waitingForReportContent.remove(telegramUserId);
            return REPORT_ALREADY_SUBMITTED_MESSAGE;
        }

        waitingForReportContent.add(telegramUserId);
        return REPORT_MESSAGE;
    }

    private String handleNormalText(Long telegramUserId, String text) {
        if (telegramUserId != null && waitingForReportContent.contains(telegramUserId)) {
            return submitReport(telegramUserId, text);
        }

        return "Bot đã nhận: " + text;
    }

    private String submitReport(Long telegramUserId, String text) {
        DailyReportSubmissionStatus status = dailyReportService.submitToday(telegramUserId, text);

        return switch (status) {
            case SAVED -> {
                waitingForReportContent.remove(telegramUserId);
                yield REPORT_SAVED_MESSAGE;
            }
            case BLANK_CONTENT -> BLANK_REPORT_MESSAGE;
            case ALREADY_SUBMITTED -> {
                waitingForReportContent.remove(telegramUserId);
                yield REPORT_ALREADY_SUBMITTED_MESSAGE;
            }
            case TELEGRAM_USER_NOT_FOUND -> {
                waitingForReportContent.remove(telegramUserId);
                yield USER_NOT_FOUND_MESSAGE;
            }
        };
    }

    private String normalizeCommand(String text) {
        String trimmedText = text == null ? "" : text.trim();
        if (!trimmedText.startsWith("/")) {
            return trimmedText;
        }

        int firstSpaceIndex = trimmedText.indexOf(' ');
        String command = firstSpaceIndex >= 0 ? trimmedText.substring(0, firstSpaceIndex) : trimmedText;
        int botUsernameIndex = command.indexOf('@');
        return botUsernameIndex >= 0 ? command.substring(0, botUsernameIndex) : command;
    }

    private Long resolveTelegramUserId(Message message) {
        User user = message.getFrom();
        return user != null ? user.getId() : null;
    }
}
