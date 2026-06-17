package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramCommandService {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandService.class);
    private static final Duration REPORT_SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final DateTimeFormatter LATEST_REPORT_CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String CALLBACK_COMMAND_PREFIX = "command:";

    private static final String START_COMMAND = "/start";
    private static final String HELP_COMMAND = "/help";
    private static final String REPORT_COMMAND = "/report";
    private static final String CANCEL_COMMAND = "/cancel";
    private static final String LAST_COMMAND = "/last";
    private static final String MINI_APP_COMMAND = "/miniapp";

    private static final String START_MESSAGE = "Xin chào! Đây là bot báo cáo công việc hằng ngày.";
    private static final String HELP_MESSAGE = "Danh sách lệnh:\n/start\n/help\n/report\n/cancel\n/last\n\nGửi /report trước mỗi lần muốn nhập báo cáo mới.";
    private static final String REPORT_MESSAGE = "Vui lòng nhập nội dung báo cáo hôm nay.";
    private static final String REPORT_SAVED_MESSAGE = "Đã lưu báo cáo hôm nay.";
    private static final String BLANK_REPORT_MESSAGE = "Nội dung báo cáo không được để trống.";
    private static final String REPORT_REQUIRED_MESSAGE = "Vui lòng gửi /report trước khi nhập báo cáo mới.";
    private static final String REPORT_SESSION_CANCELLED_MESSAGE = "Đã hủy phiên nhập báo cáo.";
    private static final String NO_ACTIVE_REPORT_SESSION_MESSAGE = "Không có phiên nhập báo cáo nào đang mở.";
    private static final String NO_LATEST_REPORT_MESSAGE = "Chưa tìm thấy báo cáo nào của bạn.";
    private static final String LATEST_REPORT_FAILED_MESSAGE = "Không thể lấy báo cáo gần nhất lúc này. Vui lòng thử lại sau.";
    private static final String REPORT_SAVE_FAILED_MESSAGE = "Không thể lưu báo cáo lúc này. Vui lòng thử lại sau.";
    private static final String USER_NOT_FOUND_MESSAGE = "Không tìm thấy người dùng Telegram.";
    private static final String MINI_APP_DISABLED_MESSAGE = "Mini app đang tạm tắt. Hãy dùng /report để gửi báo cáo trong chat Telegram.";

    private final DailyReportService dailyReportService;
    private final Clock clock;
    private final Map<ReportSessionKey, Instant> pendingReportSessions = new ConcurrentHashMap<>();

    public TelegramCommandService(DailyReportService dailyReportService, Clock clock) {
        this.dailyReportService = dailyReportService;
        this.clock = clock;
    }

    public List<BotCommand> createBotCommandMenu() {
        return List.of(
                new BotCommand("start", "Bắt đầu sử dụng bot"),
                new BotCommand("help", "Xem các lệnh có thể dùng"),
                new BotCommand("report", "Gửi báo cáo công việc hôm nay"),
                new BotCommand("cancel", "Hủy phiên nhập báo cáo hiện tại"),
                new BotCommand("last", "Xem báo cáo gần nhất của bạn"),
                new BotCommand("miniapp", "Mini App đang tạm tắt")
        );
    }

    public Optional<SendMessage> createResponse(Message message) {
        removeExpiredReportSessions();

        if (message == null || message.getChatId() == null) {
            log.warn("Cannot create Telegram response because chatId is missing.");
            return Optional.empty();
        }

        return createResponse(new CommandRequest(message.getChatId(), resolveTelegramUserId(message), message.getText()));
    }

    public Optional<SendMessage> createResponse(CallbackQuery callbackQuery) {
        removeExpiredReportSessions();

        if (callbackQuery == null || callbackQuery.getMessage() == null || callbackQuery.getMessage().getChatId() == null) {
            log.warn("Cannot create Telegram callback response because chatId is missing.");
            return Optional.empty();
        }

        String command = resolveCallbackCommand(callbackQuery.getData());
        if (command == null) {
            log.warn("Cannot create Telegram callback response because callback data is unsupported.");
            return Optional.empty();
        }

        MaybeInaccessibleMessage message = callbackQuery.getMessage();
        Long telegramUserId = callbackQuery.getFrom() != null ? callbackQuery.getFrom().getId() : null;
        return createResponse(new CommandRequest(message.getChatId(), telegramUserId, command));
    }

    private Optional<SendMessage> createResponse(CommandRequest request) {
        SendMessage response = new SendMessage();
        response.setChatId(request.chatId().toString());

        String command = normalizeCommand(request.text());
        String responseText = resolveResponseText(request, command);
        response.setText(responseText);
        response.setReplyMarkup(createSuggestedKeyboard(command, responseText));
        return Optional.of(response);
    }

    private String resolveResponseText(CommandRequest request, String command) {
        Long telegramUserId = request.telegramUserId();
        ReportSessionKey sessionKey = resolveSessionKey(request.chatId(), telegramUserId);

        return switch (command) {
            case START_COMMAND -> {
                clearReportSession(sessionKey);
                yield START_MESSAGE;
            }
            case HELP_COMMAND -> {
                clearReportSession(sessionKey);
                yield HELP_MESSAGE;
            }
            case REPORT_COMMAND -> startReportSession(sessionKey);
            case CANCEL_COMMAND -> cancelReportSession(sessionKey);
            case LAST_COMMAND -> {
                clearReportSession(sessionKey);
                yield findLatestReport(telegramUserId);
            }
            case MINI_APP_COMMAND -> {
                clearReportSession(sessionKey);
                yield MINI_APP_DISABLED_MESSAGE;
            }
            default -> submitPendingReport(sessionKey, telegramUserId, request.text());
        };
    }

    private InlineKeyboardMarkup createSuggestedKeyboard(String command, String responseText) {
        List<List<InlineKeyboardButton>> rows = switch (command) {
            case HELP_COMMAND -> List.of(
                    List.of(commandButton("📝 Gửi báo cáo", REPORT_COMMAND), commandButton("📄 Báo cáo gần nhất", LAST_COMMAND)),
                    List.of(commandButton("✖️ Hủy nhập", CANCEL_COMMAND), commandButton("🏠 Bắt đầu", START_COMMAND))
            );
            case REPORT_COMMAND -> List.of(
                    List.of(commandButton("✖️ Hủy nhập", CANCEL_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
            case CANCEL_COMMAND, LAST_COMMAND, START_COMMAND, MINI_APP_COMMAND -> List.of(
                    List.of(commandButton("📝 Gửi báo cáo", REPORT_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
            default -> createTextResponseKeyboard(responseText);
        };

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private List<List<InlineKeyboardButton>> createTextResponseKeyboard(String responseText) {
        if (REPORT_SAVED_MESSAGE.equals(responseText)) {
            return List.of(
                    List.of(commandButton("📝 Gửi báo cáo tiếp", REPORT_COMMAND), commandButton("📄 Xem báo cáo", LAST_COMMAND)),
                    List.of(commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
        }

        if (BLANK_REPORT_MESSAGE.equals(responseText)) {
            return List.of(
                    List.of(commandButton("✖️ Hủy nhập", CANCEL_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
        }

        return List.of(
                List.of(commandButton("📝 Gửi báo cáo", REPORT_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
        );
    }

    private InlineKeyboardButton commandButton(String text, String command) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(CALLBACK_COMMAND_PREFIX + command);
        return button;
    }

    private String resolveCallbackCommand(String data) {
        if (data == null || !data.startsWith(CALLBACK_COMMAND_PREFIX)) {
            return null;
        }

        return normalizeCommand(data.substring(CALLBACK_COMMAND_PREFIX.length()));
    }

    private String startReportSession(ReportSessionKey sessionKey) {
        if (sessionKey == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        pendingReportSessions.put(sessionKey, Instant.now(clock));
        return REPORT_MESSAGE;
    }

    private String cancelReportSession(ReportSessionKey sessionKey) {
        if (sessionKey == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        if (!hasActiveReportSession(sessionKey)) {
            return NO_ACTIVE_REPORT_SESSION_MESSAGE;
        }

        clearReportSession(sessionKey);
        return REPORT_SESSION_CANCELLED_MESSAGE;
    }

    private String findLatestReport(Long telegramUserId) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        try {
            return dailyReportService.findLatestForTelegramUser(telegramUserId)
                    .map(this::formatLatestReport)
                    .orElse(NO_LATEST_REPORT_MESSAGE);
        } catch (RuntimeException exception) {
            log.error("Cannot get latest Telegram report - telegramUserId={}", telegramUserId, exception);
            return LATEST_REPORT_FAILED_MESSAGE;
        }
    }

    private String formatLatestReport(DailyReport report) {
        StringBuilder message = new StringBuilder("Báo cáo gần nhất:");
        if (report.getReportDate() != null) {
            message.append("\nNgày báo cáo: ").append(report.getReportDate());
        }
        if (report.getCreatedAt() != null) {
            message.append("\nThời gian lưu: ")
                    .append(report.getCreatedAt().format(LATEST_REPORT_CREATED_AT_FORMATTER));
        }
        message.append("\nNội dung:\n").append(report.getContent());
        return message.toString();
    }

    private String submitPendingReport(ReportSessionKey sessionKey, Long telegramUserId, String text) {
        if (sessionKey == null || !hasActiveReportSession(sessionKey)) {
            return REPORT_REQUIRED_MESSAGE;
        }

        if (telegramUserId == null) {
            clearReportSession(sessionKey);
            return USER_NOT_FOUND_MESSAGE;
        }

        DailyReportSubmissionStatus status;
        try {
            status = dailyReportService.submitToday(telegramUserId, text);
        } catch (RuntimeException exception) {
            log.error("Cannot save Telegram report - telegramUserId={}", telegramUserId, exception);
            return REPORT_SAVE_FAILED_MESSAGE;
        }

        return switch (status) {
            case SAVED -> {
                clearReportSession(sessionKey);
                yield REPORT_SAVED_MESSAGE;
            }
            case BLANK_CONTENT -> BLANK_REPORT_MESSAGE;
            case TELEGRAM_USER_NOT_FOUND -> {
                clearReportSession(sessionKey);
                yield USER_NOT_FOUND_MESSAGE;
            }
        };
    }

    private void clearReportSession(ReportSessionKey sessionKey) {
        if (sessionKey != null) {
            pendingReportSessions.remove(sessionKey);
        }
    }

    private boolean hasActiveReportSession(ReportSessionKey sessionKey) {
        Instant startedAt = pendingReportSessions.get(sessionKey);
        if (startedAt == null) {
            return false;
        }

        if (isExpired(startedAt)) {
            clearReportSession(sessionKey);
            return false;
        }

        return true;
    }

    private void removeExpiredReportSessions() {
        pendingReportSessions.entrySet()
                .removeIf(entry -> isExpired(entry.getValue()));
    }

    private boolean isExpired(Instant startedAt) {
        return startedAt.plus(REPORT_SESSION_TIMEOUT).isBefore(Instant.now(clock));
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

    private ReportSessionKey resolveSessionKey(Long chatId, Long telegramUserId) {
        if (chatId == null || telegramUserId == null) {
            return null;
        }

        return new ReportSessionKey(chatId, telegramUserId);
    }

    private record ReportSessionKey(Long chatId, Long telegramUserId) {
    }

    private record CommandRequest(Long chatId, Long telegramUserId, String text) {
    }
}
