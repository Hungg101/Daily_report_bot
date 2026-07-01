package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.Employee;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelegramCommandService {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandService.class);
    private static final Duration REPORT_SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final int RECENT_REPORTS_LIMIT = 5;
    private static final int RECENT_REPORT_CONTENT_PREVIEW_LIMIT = 240;
    private static final DateTimeFormatter LATEST_REPORT_CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String CALLBACK_COMMAND_PREFIX = "command:";

    private static final String START_COMMAND = "/start";
    private static final String HELP_COMMAND = "/help";
    private static final String REPORT_COMMAND = "/report";
    private static final String STATUS_COMMAND = "/status";
    private static final String CANCEL_COMMAND = "/cancel";
    private static final String LAST_COMMAND = "/last";
    private static final String MY_REPORTS_COMMAND = "/myreports";
    private static final String REPORTS_BY_DATE_COMMAND = "/reports";
    private static final String LINK_COMMAND = "/link";
    private static final String WHOAMI_COMMAND = "/whoami";
    private static final String MINI_APP_COMMAND = "/miniapp";

    private static final String START_MESSAGE = "Xin chào! Đây là bot báo cáo công việc hằng ngày.";
    private static final String HELP_MESSAGE = "Danh sách lệnh:\n/start\n/help\n/report\n/status\n/cancel\n/last\n/myreports\n/reports YYYY-MM-DD\n/link EMPLOYEE_CODE\n/whoami\n\nGửi /report trước mỗi lần muốn nhập báo cáo mới.";
    private static final String REPORT_MESSAGE = "Vui lòng nhập nội dung báo cáo hôm nay.";
    private static final String REPORT_SAVED_MESSAGE = "Đã lưu báo cáo hôm nay.";
    private static final String REPORT_STATUS_FAILED_MESSAGE = "Không thể lấy trạng thái báo cáo hôm nay lúc này. Vui lòng thử lại sau.";
    private static final String BLANK_REPORT_MESSAGE = "Nội dung báo cáo không được để trống.";
    private static final String REPORT_REQUIRED_MESSAGE = "Vui lòng gửi /report trước khi nhập báo cáo mới.";
    private static final String REPORT_SESSION_CANCELLED_MESSAGE = "Đã hủy phiên nhập báo cáo.";
    private static final String NO_ACTIVE_REPORT_SESSION_MESSAGE = "Không có phiên nhập báo cáo nào đang mở.";
    private static final String NO_LATEST_REPORT_MESSAGE = "Chưa tìm thấy báo cáo nào của bạn.";
    private static final String LATEST_REPORT_FAILED_MESSAGE = "Không thể lấy báo cáo gần nhất lúc này. Vui lòng thử lại sau.";
    private static final String NO_RECENT_REPORTS_MESSAGE = "Chưa tìm thấy báo cáo nào của bạn.";
    private static final String RECENT_REPORTS_FAILED_MESSAGE = "Không thể lấy danh sách báo cáo lúc này. Vui lòng thử lại sau.";
    private static final String REPORTS_BY_DATE_USAGE_MESSAGE = "Cú pháp: /reports YYYY-MM-DD\nVí dụ: /reports 2026-06-17";
    private static final String INVALID_REPORT_DATE_MESSAGE = "Ngày không hợp lệ. Hãy dùng định dạng YYYY-MM-DD, ví dụ /reports 2026-06-17.";
    private static final String REPORTS_BY_DATE_FAILED_MESSAGE = "Không thể lấy báo cáo theo ngày lúc này. Vui lòng thử lại sau.";
    private static final String REPORT_SAVE_FAILED_MESSAGE = "Không thể lưu báo cáo lúc này. Vui lòng thử lại sau.";
    private static final String USER_NOT_FOUND_MESSAGE = "Không tìm thấy người dùng Telegram.";
    private static final String MINI_APP_DISABLED_MESSAGE = "Mini app đang tạm tắt. Hãy dùng /report để gửi báo cáo trong chat Telegram.";

    private static final String LINK_USAGE_MESSAGE = "Cú pháp: /link EMPLOYEE_CODE\nVí dụ: /link EMP001";
    private static final String LINKED_MESSAGE_PREFIX = "Đã liên kết tài khoản Telegram của bạn với mã nhân viên ";
    private static final String LINK_EMPLOYEE_NOT_FOUND_MESSAGE = "Không tìm thấy nhân viên với mã này. Hãy kiểm tra lại mã nhân viên.";
    private static final String LINK_TELEGRAM_ALREADY_LINKED_MESSAGE = "Tài khoản Telegram của bạn đã được liên kết với nhân viên. Dùng /whoami để kiểm tra.";
    private static final String LINK_EMPLOYEE_ALREADY_LINKED_MESSAGE = "Mã nhân viên này đã được liên kết với tài khoản Telegram khác.";
    private static final String LINK_EMPLOYEE_INACTIVE_MESSAGE = "Nhân viên này đang ngừng hoạt động, không thể liên kết.";
    private static final String LINK_FAILED_MESSAGE = "Không thể liên kết tài khoản lúc này. Vui lòng thử lại sau.";
    private static final String WHOAMI_NOT_LINKED_MESSAGE = "Tài khoản Telegram của bạn chưa được liên kết với nhân viên nào.";
    private static final String WHOAMI_FAILED_MESSAGE = "Không thể lấy thông tin liên kết lúc này. Vui lòng thử lại sau.";

    private final DailyReportService dailyReportService;
    private final TelegramUserMappingService telegramUserMappingService;
    private final Clock clock;
    private final Map<ReportSessionKey, Instant> pendingReportSessions = new ConcurrentHashMap<>();

    public TelegramCommandService(
            DailyReportService dailyReportService,
            TelegramUserMappingService telegramUserMappingService,
            Clock clock
    ) {
        this.dailyReportService = dailyReportService;
        this.telegramUserMappingService = telegramUserMappingService;
        this.clock = clock;
    }

    public List<BotCommand> createBotCommandMenu() {
        return List.of(
                new BotCommand("start", "Bắt đầu sử dụng bot"),
                new BotCommand("help", "Xem các lệnh có thể dùng"),
                new BotCommand("report", "Gửi báo cáo công việc hôm nay"),
                new BotCommand("status", "Xem trạng thái báo cáo hôm nay"),
                new BotCommand("cancel", "Hủy phiên nhập báo cáo hiện tại"),
                new BotCommand("last", "Xem báo cáo gần nhất của bạn"),
                new BotCommand("myreports", "Xem 5 báo cáo gần đây của bạn"),
                new BotCommand("reports", "Xem báo cáo của bạn theo ngày"),
                new BotCommand("link", "Liên kết Telegram với mã nhân viên"),
                new BotCommand("whoami", "Xem liên kết Telegram với nhân viên"),
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
            case STATUS_COMMAND -> {
                clearReportSession(sessionKey);
                yield findTodayReportStatus(telegramUserId);
            }
            case CANCEL_COMMAND -> cancelReportSession(sessionKey);
            case LAST_COMMAND -> {
                clearReportSession(sessionKey);
                yield findLatestReport(telegramUserId);
            }
            case MY_REPORTS_COMMAND -> {
                clearReportSession(sessionKey);
                yield findRecentReports(telegramUserId);
            }
            case REPORTS_BY_DATE_COMMAND -> {
                clearReportSession(sessionKey);
                yield findReportsByDate(telegramUserId, request.text());
            }
            case LINK_COMMAND -> {
                clearReportSession(sessionKey);
                yield linkTelegramUserToEmployee(telegramUserId, request.text());
            }
            case WHOAMI_COMMAND -> {
                clearReportSession(sessionKey);
                yield findMappedEmployee(telegramUserId);
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
                    List.of(commandButton("📊 Trạng thái hôm nay", STATUS_COMMAND), commandButton("📚 5 báo cáo gần đây", MY_REPORTS_COMMAND)),
                    List.of(commandButton("🔗 Liên kết tài khoản", LINK_COMMAND), commandButton("👤 Tài khoản của tôi", WHOAMI_COMMAND)),
                    List.of(commandButton("✖️ Hủy nhập", CANCEL_COMMAND), commandButton("🏠 Bắt đầu", START_COMMAND))
            );
            case REPORT_COMMAND -> List.of(
                    List.of(commandButton("✖️ Hủy nhập", CANCEL_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
            case STATUS_COMMAND, CANCEL_COMMAND, LAST_COMMAND, MY_REPORTS_COMMAND, REPORTS_BY_DATE_COMMAND, START_COMMAND, MINI_APP_COMMAND -> List.of(
                    List.of(commandButton("📝 Gửi báo cáo", REPORT_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
            case LINK_COMMAND, WHOAMI_COMMAND -> List.of(
                    List.of(commandButton("👤 Tài khoản của tôi", WHOAMI_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND)),
                    List.of(commandButton("📝 Gửi báo cáo", REPORT_COMMAND))
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
                    List.of(commandButton("📝 Gửi báo cáo tiếp", REPORT_COMMAND), commandButton("📊 Trạng thái", STATUS_COMMAND)),
                    List.of(commandButton("📄 Xem báo cáo", LAST_COMMAND)),
                    List.of(commandButton("📚 5 báo cáo gần đây", MY_REPORTS_COMMAND)),
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

    private String findRecentReports(Long telegramUserId) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        try {
            List<DailyReport> reports = dailyReportService.findRecentForTelegramUser(telegramUserId, RECENT_REPORTS_LIMIT);
            if (reports.isEmpty()) {
                return NO_RECENT_REPORTS_MESSAGE;
            }

            return formatRecentReports(reports);
        } catch (RuntimeException exception) {
            log.error("Cannot get recent Telegram reports - telegramUserId={}", telegramUserId, exception);
            return RECENT_REPORTS_FAILED_MESSAGE;
        }
    }

    private String findTodayReportStatus(Long telegramUserId) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        LocalDate reportDate = LocalDate.now(clock);
        try {
            List<DailyReport> reports = dailyReportService.findForTelegramUserOnDate(telegramUserId, reportDate);
            if (reports.isEmpty()) {
                return "Trạng thái báo cáo hôm nay (" + reportDate + "):\nChưa có báo cáo nào. Gửi /report để nộp báo cáo hôm nay.";
            }

            return formatTodayReportStatus(reportDate, reports);
        } catch (RuntimeException exception) {
            log.error(
                    "Cannot get today's Telegram report status - telegramUserId={}, reportDate={}",
                    telegramUserId,
                    reportDate,
                    exception
            );
            return REPORT_STATUS_FAILED_MESSAGE;
        }
    }

    private String findReportsByDate(Long telegramUserId, String text) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        String reportDateText = extractSingleCommandArgument(text);
        if (reportDateText == null) {
            return REPORTS_BY_DATE_USAGE_MESSAGE;
        }

        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(reportDateText);
        } catch (DateTimeParseException exception) {
            return INVALID_REPORT_DATE_MESSAGE;
        }

        try {
            List<DailyReport> reports = dailyReportService.findForTelegramUserOnDate(telegramUserId, reportDate);
            if (reports.isEmpty()) {
                return "Chưa tìm thấy báo cáo nào của bạn trong ngày " + reportDate + ".";
            }

            return formatReportsByDate(reportDate, reports);
        } catch (RuntimeException exception) {
            log.error(
                    "Cannot get Telegram reports by date - telegramUserId={}, reportDate={}",
                    telegramUserId,
                    reportDate,
                    exception
            );
            return REPORTS_BY_DATE_FAILED_MESSAGE;
        }
    }

    private String findMappedEmployee(Long telegramUserId) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        try {
            return telegramUserMappingService.findMappedEmployee(telegramUserId)
                    .map(employee -> formatMappedEmployee(telegramUserId, employee))
                    .orElse(WHOAMI_NOT_LINKED_MESSAGE);
        } catch (RuntimeException exception) {
            log.error("Cannot get mapped employee - telegramUserId={}", telegramUserId, exception);
            return WHOAMI_FAILED_MESSAGE;
        }
    }

    private String linkTelegramUserToEmployee(Long telegramUserId, String text) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        String employeeCode = extractSingleCommandArgument(text);
        if (employeeCode == null) {
            return LINK_USAGE_MESSAGE;
        }

        try {
            TelegramUserMappingStatus status = telegramUserMappingService.linkTelegramUserToEmployee(
                    telegramUserId,
                    employeeCode
            );
            return formatLinkStatus(status, employeeCode);
        } catch (RuntimeException exception) {
            log.error(
                    "Cannot link Telegram user to employee - telegramUserId={}, employeeCode={}",
                    telegramUserId,
                    employeeCode,
                    exception
            );
            return LINK_FAILED_MESSAGE;
        }
    }

    private String formatLinkStatus(TelegramUserMappingStatus status, String employeeCode) {
        return switch (status) {
            case LINKED -> LINKED_MESSAGE_PREFIX + employeeCode + ".\nDùng /whoami để kiểm tra.";
            case TELEGRAM_USER_NOT_FOUND -> USER_NOT_FOUND_MESSAGE;
            case EMPLOYEE_NOT_FOUND -> LINK_EMPLOYEE_NOT_FOUND_MESSAGE;
            case TELEGRAM_USER_ALREADY_LINKED -> LINK_TELEGRAM_ALREADY_LINKED_MESSAGE;
            case EMPLOYEE_ALREADY_LINKED -> LINK_EMPLOYEE_ALREADY_LINKED_MESSAGE;
            case EMPLOYEE_INACTIVE -> LINK_EMPLOYEE_INACTIVE_MESSAGE;
        };
    }

    private String formatMappedEmployee(Long telegramUserId, Employee employee) {
        StringBuilder message = new StringBuilder("Thông tin tài khoản:");
        message.append("\nTelegram user ID: ").append(telegramUserId);
        appendIfPresent(message, "Mã nhân viên", employee.getCode());
        appendIfPresent(message, "Họ tên", employee.getFullName());
        if (employee.getStatus() != null) {
            message.append("\nTrạng thái: ").append(formatEmployeeStatus(employee));
        }
        return message.toString();
    }

    private String formatEmployeeStatus(Employee employee) {
        return switch (employee.getStatus()) {
            case ACTIVE -> "Đang hoạt động";
            case INACTIVE -> "Ngừng hoạt động";
        };
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append("\n").append(label).append(": ").append(value);
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

    private String formatRecentReports(List<DailyReport> reports) {
        StringBuilder message = new StringBuilder("5 báo cáo gần đây của bạn:");
        appendReportList(message, reports);
        return message.toString();
    }

    private String formatTodayReportStatus(LocalDate reportDate, List<DailyReport> reports) {
        StringBuilder message = new StringBuilder("Trạng thái báo cáo hôm nay (")
                .append(reportDate)
                .append("):");
        message.append("\nĐã có ").append(reports.size()).append(" báo cáo.");

        DailyReport latestReport = reports.get(0);
        if (latestReport.getCreatedAt() != null) {
            message.append("\nLần mới nhất lưu lúc: ")
                    .append(latestReport.getCreatedAt().format(LATEST_REPORT_CREATED_AT_FORMATTER));
        }
        message.append("\nNội dung mới nhất:\n").append(createContentPreview(latestReport.getContent()));
        return message.toString();
    }

    private String formatReportsByDate(LocalDate reportDate, List<DailyReport> reports) {
        StringBuilder message = new StringBuilder("Báo cáo ngày ")
                .append(reportDate)
                .append(" của bạn:");
        appendReportList(message, reports);
        return message.toString();
    }

    private void appendReportList(StringBuilder message, List<DailyReport> reports) {
        for (int index = 0; index < reports.size(); index += 1) {
            DailyReport report = reports.get(index);
            message.append("\n\n").append(index + 1).append(". ");
            if (report.getReportDate() != null) {
                message.append("Ngày báo cáo: ").append(report.getReportDate());
            } else {
                message.append("Báo cáo");
            }
            if (report.getCreatedAt() != null) {
                message.append("\nThời gian lưu: ")
                        .append(report.getCreatedAt().format(LATEST_REPORT_CREATED_AT_FORMATTER));
            }
            message.append("\nNội dung:\n").append(createContentPreview(report.getContent()));
        }
    }

    private String createContentPreview(String content) {
        String normalizedContent = content == null ? "" : content.strip();
        if (normalizedContent.length() <= RECENT_REPORT_CONTENT_PREVIEW_LIMIT) {
            return normalizedContent;
        }

        return normalizedContent.substring(0, RECENT_REPORT_CONTENT_PREVIEW_LIMIT).stripTrailing() + "...";
    }

    private String extractSingleCommandArgument(String text) {
        String trimmedText = text == null ? "" : text.trim();
        int firstSpaceIndex = trimmedText.indexOf(' ');
        if (firstSpaceIndex < 0) {
            return null;
        }

        String arguments = trimmedText.substring(firstSpaceIndex + 1).trim();
        if (arguments.isBlank()) {
            return null;
        }

        String[] tokens = arguments.split("\\s+");
        return tokens.length == 1 ? tokens[0] : null;
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
