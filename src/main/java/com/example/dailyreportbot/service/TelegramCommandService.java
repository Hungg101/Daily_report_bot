package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * Dịch vụ xử lý các lệnh (commands) từ người dùng trên Telegram.
 * Tiếp nhận các yêu cầu như nộp báo cáo, quản lý nhân sự, xem báo cáo
 * và điều phối xử lý đến các Service tương ứng.
 */
public class TelegramCommandService {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandService.class);
    private static final Duration REPORT_SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final int RECENT_REPORTS_LIMIT = 5;
    private static final int RECENT_REPORT_CONTENT_PREVIEW_LIMIT = 240;
    private static final int MANAGEMENT_USER_LIMIT = 50;
    private static final int MANAGEMENT_AUDIT_LIMIT = 10;
    private static final int MANAGEMENT_MESSAGE_BUDGET = 3500;
    private static final int MANAGEMENT_AUDIT_REASON_LIMIT = 160;
    private static final DateTimeFormatter REPORT_CREATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String CALLBACK_COMMAND_PREFIX = "command:";
    private static final String REPORT_CALLBACK_PREFIX = "report:";
    private static final String REPORT_CALLBACK_SOLO = "n";
    private static final String REPORT_CALLBACK_EDIT_TITLE = "t";
    private static final String REPORT_CALLBACK_EDIT_COLLABORATORS = "c";
    private static final String REPORT_CALLBACK_EDIT_CONTENT = "b";
    private static final String REPORT_CALLBACK_CONFIRM = "y";
    private static final String REPORT_CALLBACK_CANCEL = "x";

    private static final String START_COMMAND = "/start";
    private static final String HELP_COMMAND = "/help";
    private static final String REPORT_COMMAND = "/report";
    private static final String STATUS_COMMAND = "/status";
    private static final String CANCEL_COMMAND = "/cancel";
    private static final String MY_REPORTS_COMMAND = "/myreports";
    private static final String REPORTS_BY_DATE_COMMAND = "/reports";
    private static final String EMPLOYEE_INFORMATION_COMMAND = "/employeeinfo";
    private static final String TEAM_STATUS_COMMAND = "/teamstatus";
    private static final String MANAGE_COMMAND = "/manage";
    private static final String MINI_APP_COMMAND = "/miniapp";
    private static final Set<String> SESSION_CLEARING_COMMANDS = Set.of(
            START_COMMAND, HELP_COMMAND, STATUS_COMMAND, MY_REPORTS_COMMAND,
            REPORTS_BY_DATE_COMMAND, EMPLOYEE_INFORMATION_COMMAND, TEAM_STATUS_COMMAND,
            MANAGE_COMMAND, MINI_APP_COMMAND
    );

    private static final String START_MESSAGE = "Xin chào! Đây là bot báo cáo công việc hằng ngày.";
    private static final String HELP_MESSAGE = "Danh sách lệnh:\n/start\n/help\n/report\n/status\n/cancel\n/myreports\n/reports YYYY-MM-DD\n/employeeinfo\n/ai <câu hỏi>\n\nGửi /report trước mỗi lần muốn nhập báo cáo mới.";
    private static final String REPORT_TITLE_MESSAGE = "Vui lòng nhập Tiêu đề (tối đa 200 ký tự).";
    private static final String REPORT_COLLABORATORS_MESSAGE =
            "Vui lòng nhập Người cùng thực hiện (tối đa 500 ký tự).";
    private static final String REPORT_CONTENT_MESSAGE =
            "Vui lòng nhập Nội dung báo cáo (tối đa 3000 ký tự).";
    private static final String REPORT_SAVED_MESSAGE = "✅ Đã lưu báo cáo.";
    private static final String REPORT_STATUS_FAILED_MESSAGE = "Không thể lấy trạng thái báo cáo hôm nay lúc này. Vui lòng thử lại sau.";
    private static final String BLANK_REPORT_MESSAGE = "Nội dung báo cáo không được để trống.";
    private static final String BLANK_TITLE_MESSAGE = "Tiêu đề không được để trống.";
    private static final String LONG_TITLE_MESSAGE = "Tiêu đề không được dài quá 200 ký tự.";
    private static final String BLANK_COLLABORATORS_MESSAGE =
            "Người cùng thực hiện không được để trống; hãy dùng nút Tôi làm một mình nếu phù hợp.";
    private static final String LONG_COLLABORATORS_MESSAGE =
            "Người cùng thực hiện không được dài quá 500 ký tự.";
    private static final String LONG_REPORT_CONTENT_MESSAGE =
            "Nội dung báo cáo không được dài quá 3000 ký tự.";
    private static final String INACTIVE_REPORT_MESSAGE =
            "Tài khoản đang ngừng hoạt động nên không thể gửi báo cáo mới.";
    private static final String REPORT_REQUIRED_MESSAGE = "Vui lòng gửi /report trước khi nhập báo cáo mới.";
    private static final String REPORT_PREVIEW_INPUT_MESSAGE =
            "Vui lòng dùng các nút bên dưới để chỉnh sửa, xác nhận hoặc hủy báo cáo.";
    private static final String REPORT_ACTION_EXPIRED_MESSAGE =
            "Thao tác báo cáo này đã hết hiệu lực. Hãy gửi /report để bắt đầu lại.";
    private static final String REPORT_SESSION_EXPIRED_MESSAGE =
            "Phiên nhập báo cáo đã hết hạn. Hãy gửi /report để bắt đầu lại.";
    private static final String REPORT_SESSION_CANCELLED_MESSAGE = "Đã hủy phiên nhập báo cáo.";
    private static final String NO_ACTIVE_REPORT_SESSION_MESSAGE = "Không có phiên nhập báo cáo nào đang mở.";
    private static final String NO_RECENT_REPORTS_MESSAGE = "Chưa tìm thấy báo cáo nào của bạn.";
    private static final String RECENT_REPORTS_FAILED_MESSAGE = "Không thể lấy danh sách báo cáo lúc này. Vui lòng thử lại sau.";
    private static final String REPORTS_BY_DATE_USAGE_MESSAGE = "Cú pháp: /reports YYYY-MM-DD\nVí dụ: /reports 2026-06-17";
    private static final String INVALID_REPORT_DATE_MESSAGE = "Ngày không hợp lệ. Hãy dùng định dạng YYYY-MM-DD, ví dụ /reports 2026-06-17.";
    private static final String REPORTS_BY_DATE_FAILED_MESSAGE = "Không thể lấy báo cáo theo ngày lúc này. Vui lòng thử lại sau.";
    private static final String REPORT_SAVE_FAILED_MESSAGE = "Không thể lưu báo cáo lúc này. Vui lòng thử lại sau.";
    private static final String REPORT_IDENTITY_CHANGED_MESSAGE =
            "Danh tính Telegram đã thay đổi. Hãy gửi /report để bắt đầu lại.";
    private static final String USER_NOT_FOUND_MESSAGE = "Không tìm thấy người dùng Telegram.";
    private static final String MINI_APP_DISABLED_MESSAGE = "Mini app đang tạm tắt. Hãy dùng /report để gửi báo cáo trong chat Telegram.";
    private static final String MINI_APP_OPEN_MESSAGE = "Mở Mini App để nhập báo cáo hoặc dùng /report trong chat Telegram.";
    private static final String MINI_APP_PRIVATE_MESSAGE =
            "Mini App chỉ mở từ chat riêng với bot. Hãy quay lại chat riêng hoặc dùng /report.";
    private static final String UNSUPPORTED_COMMAND_MESSAGE =
            "Lệnh không được hỗ trợ.\n\n" + HELP_MESSAGE;

    private static final String EMPLOYEE_INFORMATION_FAILED_MESSAGE =
            "Không thể lấy thông tin nhân viên lúc này. Vui lòng thử lại sau.";
    private static final String TEAM_STATUS_DENIED_MESSAGE =
            "Bạn không có quyền xem trạng thái đội nhóm.";
    private static final String TEAM_STATUS_FAILED_MESSAGE =
            "Không thể lấy trạng thái đội nhóm lúc này. Vui lòng thử lại sau.";
    private static final String TEAM_STATUS_HELP_SUFFIX = "\n/teamstatus";
    private static final String MANAGEMENT_HELP_SUFFIX = "\n/manage — xem hướng dẫn quản trị";
    private static final String MANAGEMENT_HELP_MESSAGE = """
            Hướng dẫn quản trị:
            /manage users [keyword]
            /manage user-info <telegramUserId>
            /manage reports <telegramUserId>|<YYYY-MM-DD>
            /manage recent-reports <telegramUserId>
            /manage report-info <reportId>
            /manage department-reports <departmentName>|<YYYY-MM-DD>
            /manage missing <departmentName>|<YYYY-MM-DD>
            /manage organization-summary <YYYY-MM-DD>
            /manage audit <telegramUserId>
            /manage user-update <telegramUserId>|<employeeCode>|<fullName>|<unitName>|<reason>
            /manage user-status <telegramUserId>|<ACTIVE_OR_INACTIVE>|<reason>
            /manage user-department <telegramUserId>|<departmentName>|<unitName>|<reason>
            /manage report-edit <reportId>|<newContent>|<reason>
            /manage report-delete <reportId>|<reason>
            /manage role <telegramUserId>|<MANAGER_OR_ADMIN>|<GRANT_OR_REVOKE>|<reason>
            """.strip();
    private static final String MANAGEMENT_SUCCESS_MESSAGE = "Đã cập nhật thành công.";
    private static final String MANAGEMENT_DELETED_MESSAGE = "Đã xóa thành công.";
    private static final String MANAGEMENT_NO_CHANGE_MESSAGE = "Không có thay đổi.";
    private static final String MANAGEMENT_DENIED_MESSAGE =
            "Bạn không có quyền thực hiện thao tác quản trị này.";
    private static final String MANAGEMENT_NOT_FOUND_MESSAGE = "Không tìm thấy đối tượng quản trị.";
    private static final String MANAGEMENT_CONFLICT_MESSAGE =
            "Dữ liệu đã thay đổi; vui lòng thử lại.";
    private static final String MANAGEMENT_INVALID_MESSAGE = "Yêu cầu quản trị không hợp lệ.";
    private static final String MANAGEMENT_FAILED_MESSAGE =
            "Không thể thực hiện thao tác quản trị lúc này.";
    private static final String MANAGEMENT_NO_DATA_MESSAGE = "Chưa có dữ liệu quản trị phù hợp.";

    private final DailyReportService dailyReportService;
    private final UserProfileService userProfileService;
    private final TeamReportReadService teamReportReadService;
    private final IdentityAdministrationService identityAdministrationService;
    private final Clock clock;
    private final TelegramBotProperties botProperties;
    // ponytail: linear in-memory expiry cleanup fits one demo instance; use shared TTL storage only for multi-instance deployment.
    private final Map<ReportSessionKey, ReportDraft> pendingReportSessions = new ConcurrentHashMap<>();

    public TelegramCommandService(
            DailyReportService dailyReportService,
            UserProfileService userProfileService,
            TeamReportReadService teamReportReadService,
            IdentityAdministrationService identityAdministrationService,
            Clock clock,
            TelegramBotProperties botProperties
    ) {
        this.dailyReportService = dailyReportService;
        this.userProfileService = userProfileService;
        this.teamReportReadService = teamReportReadService;
        this.identityAdministrationService = identityAdministrationService;
        this.clock = clock;
        this.botProperties = botProperties;
    }

    /**
     * Khởi tạo menu lệnh (Command Menu) hiển thị trên giao diện Telegram của người dùng.
     * Trả về danh sách các lệnh chuẩn như /start, /help, /report, /myreports, v.v.
     */
    public List<BotCommand> createBotCommandMenu() {
        return List.of(
                new BotCommand("start", "Bắt đầu sử dụng bot"),
                new BotCommand("help", "Xem các lệnh có thể dùng"),
                new BotCommand("report", "Gửi báo cáo công việc hôm nay"),
                new BotCommand("status", "Xem trạng thái báo cáo hôm nay"),
                new BotCommand("cancel", "Hủy phiên nhập báo cáo hiện tại"),
                new BotCommand("myreports", "Xem 5 báo cáo gần đây của bạn"),
                new BotCommand("reports", "Xem báo cáo của bạn theo ngày"),
                new BotCommand("employeeinfo", "Xem thông tin nhân viên"),
                new BotCommand("ai", "Hỏi AI về báo cáo công việc"),
                new BotCommand("teamstatus", "Xem trạng thái đội nhóm"),
                new BotCommand("manage", "Thực hiện thao tác quản trị"),
                new BotCommand("miniapp", botProperties.validMiniAppUrl().isPresent()
                        ? "Gửi báo cáo bằng Mini App"
                        : "Mini App đang tạm tắt")
        );
    }

    /**
     * Điểm neo xử lý mọi tin nhắn văn bản (text message) từ người dùng Telegram.
     * Nhận dạng lệnh (ví dụ: /report, /ai, /status) và điều hướng yêu cầu
     * đến các lớp xử lý nghiệp vụ cụ thể.
     */
    public Optional<SendMessage> createResponse(Message message) {
        if (message == null || message.getChatId() == null) {
            log.warn("Cannot create Telegram response because chatId is missing.");
            return Optional.empty();
        }

        Long telegramUserId = resolveTelegramUserId(message);
        ReportSessionKey sessionKey = resolveSessionKey(message.getChatId(), telegramUserId);
        boolean reportSessionExpired = removeExpiredReportSession(sessionKey);
        removeExpiredReportSessions();
        return createResponse(new CommandRequest(
                message.getChatId(),
                telegramUserId,
                message.getText(),
                reportSessionExpired,
                message.isUserMessage()
        ));
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
        boolean privateChat = message instanceof Message accessibleMessage && accessibleMessage.isUserMessage();
        return createResponse(new CommandRequest(message.getChatId(), telegramUserId, command, false, privateChat));
    }

    private Optional<SendMessage> createResponse(CommandRequest request) {
        String command = normalizeCommand(request.text());
        String responseText = resolveResponseText(request, command);
        SendMessage response = new SendMessage(request.chatId().toString(), responseText);
        response.setReplyMarkup(createSuggestedKeyboard(
                command,
                responseText,
                resolveSessionKey(request.chatId(), request.telegramUserId()),
                request.privateChat()
        ));
        return Optional.of(response);
    }

    /**
     * Hàm nội bộ: Phân giải lệnh (command) và xử lý logic tương ứng.
     * Đây là "trái tim" của class, chịu trách nhiệm chuyển hướng yêu cầu (như /report, /status)
     * tới các hàm xử lý chuyên biệt và trả về chuỗi văn bản kết quả cho Telegram.
     */
    private String resolveResponseText(CommandRequest request, String command) {
        Long telegramUserId = request.telegramUserId();
        ReportSessionKey sessionKey = resolveSessionKey(request.chatId(), telegramUserId);
        if (command.startsWith(REPORT_CALLBACK_PREFIX)) {
            return handleReportCallback(sessionKey, telegramUserId, command);
        }
        if (command.startsWith("/")
                && !SESSION_CLEARING_COMMANDS.contains(command)
                && !REPORT_COMMAND.equals(command)
                && !CANCEL_COMMAND.equals(command)) {
            return UNSUPPORTED_COMMAND_MESSAGE;
        }
        if (SESSION_CLEARING_COMMANDS.contains(command)) {
            clearReportSession(sessionKey);
        }

        return switch (command) {
            case START_COMMAND -> START_MESSAGE;
            case HELP_COMMAND -> HELP_MESSAGE + TEAM_STATUS_HELP_SUFFIX + MANAGEMENT_HELP_SUFFIX;
            case REPORT_COMMAND -> startReportSession(sessionKey);
            case STATUS_COMMAND -> findTodayReportStatus(telegramUserId);
            case CANCEL_COMMAND -> cancelReportSession(sessionKey);
            case MY_REPORTS_COMMAND -> findRecentReports(telegramUserId);
            case REPORTS_BY_DATE_COMMAND -> findReportsByDate(telegramUserId, request.text());
            case EMPLOYEE_INFORMATION_COMMAND -> findEmployeeInformation(telegramUserId);
            case TEAM_STATUS_COMMAND -> findTeamStatus(telegramUserId, request.privateChat());
            case MANAGE_COMMAND -> manage(telegramUserId, request.privateChat(), request.text());
            case MINI_APP_COMMAND -> miniAppMessage(request.privateChat());
            default -> request.reportSessionExpired()
                    ? REPORT_SESSION_EXPIRED_MESSAGE
                    : handleReportInput(sessionKey, telegramUserId, request.text());
        };
    }

    /**
     * Hàm nội bộ: Tạo bàn phím ảo (Inline Keyboard) tùy chỉnh theo ngữ cảnh của câu trả lời.
     * Gợi ý các thao tác tiếp theo để người dùng dễ dàng bấm chọn (ví dụ: Bấm "Nhập báo cáo" sau lệnh /start).
     */
    private InlineKeyboardMarkup createSuggestedKeyboard(
            String command,
            String responseText,
            ReportSessionKey sessionKey,
            boolean privateChat
    ) {
        List<List<InlineKeyboardButton>> reportRows = REPORT_ACTION_EXPIRED_MESSAGE.equals(responseText)
                ? null
                : createReportKeyboard(sessionKey);
        if (reportRows != null) {
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            markup.setKeyboard(reportRows);
            return markup;
        }

        List<List<InlineKeyboardButton>> rows = command.equals(MINI_APP_COMMAND)
                && privateChat
                && botProperties.validMiniAppUrl().isPresent()
                ? List.of(List.of(webAppButton(botProperties.validMiniAppUrl().orElseThrow())))
                : switch (command) {
            case HELP_COMMAND -> List.of(
                    List.of(commandButton("📝 Nhập báo cáo", REPORT_COMMAND)),
                    List.of(commandButton("📊 Trạng thái hôm nay", STATUS_COMMAND), commandButton("📚 5 báo cáo gần đây", MY_REPORTS_COMMAND)),
                    List.of(commandButton("👤 Thông tin nhân viên", EMPLOYEE_INFORMATION_COMMAND)),
                    List.of(commandButton("👥 Quản trị", MANAGE_COMMAND)),
                    List.of(commandButton("✖️ Hủy nhập", CANCEL_COMMAND), commandButton("🏠 Bắt đầu", START_COMMAND))
            );
            case MANAGE_COMMAND -> createManagementKeyboard();
            case STATUS_COMMAND, CANCEL_COMMAND, MY_REPORTS_COMMAND, REPORTS_BY_DATE_COMMAND, START_COMMAND, MINI_APP_COMMAND -> List.of(
                    List.of(commandButton("📝 Nhập báo cáo", REPORT_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
            );
            case EMPLOYEE_INFORMATION_COMMAND -> List.of(
                    List.of(commandButton("👤 Thông tin nhân viên", EMPLOYEE_INFORMATION_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND)),
                    List.of(commandButton("📝 Nhập báo cáo", REPORT_COMMAND))
            );
            default -> createTextResponseKeyboard(responseText);
                };

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private List<List<InlineKeyboardButton>> createTextResponseKeyboard(String responseText) {
        if (responseText.startsWith(REPORT_SAVED_MESSAGE)) {
            return List.of(
                    List.of(commandButton("📝 Nhập báo cáo tiếp", REPORT_COMMAND), commandButton("📊 Trạng thái", STATUS_COMMAND)),
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
                List.of(commandButton("📝 Nhập báo cáo", REPORT_COMMAND), commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
        );
    }

    private List<List<InlineKeyboardButton>> createManagementKeyboard() {
        return List.of(
                List.of(commandButton("👥 Người dùng", "/manage users")),
                List.of(commandButton("👤 Thông tin user", "/manage user-info")),
                List.of(commandButton("📑 Báo cáo phòng/ngày", "/manage department-reports")),
                List.of(commandButton("❌ Chưa nộp", "/manage missing")),
                List.of(commandButton("🕘 Báo cáo gần nhất", "/manage recent-reports")),
                List.of(commandButton("📊 Trạng thái đội", TEAM_STATUS_COMMAND)),
                List.of(commandButton("ℹ️ Trợ giúp", HELP_COMMAND))
        );
    }

    private InlineKeyboardButton commandButton(String text, String command) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(CALLBACK_COMMAND_PREFIX + command);
        return button;
    }

    private InlineKeyboardButton webAppButton(String url) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText("Mở Mini App");
        button.setWebApp(new WebAppInfo(url));
        return button;
    }

    private String miniAppMessage(boolean privateChat) {
        if (!privateChat) {
            return MINI_APP_PRIVATE_MESSAGE;
        }
        return botProperties.validMiniAppUrl().isPresent()
                ? MINI_APP_OPEN_MESSAGE
                : MINI_APP_DISABLED_MESSAGE;
    }

    private List<List<InlineKeyboardButton>> createReportKeyboard(ReportSessionKey sessionKey) {
        ReportDraft draft = findActiveReportDraft(sessionKey);
        if (draft == null) {
            return null;
        }

        synchronized (draft) {
            if (pendingReportSessions.get(sessionKey) != draft) {
                return null;
            }

            return switch (draft.step) {
                case COLLABORATORS, EDIT_COLLABORATORS -> List.of(
                        List.of(reportButton("👤 Không - Tôi làm một mình", draft, REPORT_CALLBACK_SOLO)),
                        List.of(reportButton("❌ Hủy", draft, REPORT_CALLBACK_CANCEL))
                );
                case PREVIEW -> List.of(
                        List.of(
                                reportButton("✏️ Tiêu đề", draft, REPORT_CALLBACK_EDIT_TITLE),
                                reportButton("👥 Người cùng thực hiện", draft, REPORT_CALLBACK_EDIT_COLLABORATORS)
                        ),
                        List.of(reportButton("📝 Nội dung", draft, REPORT_CALLBACK_EDIT_CONTENT)),
                        List.of(
                                reportButton("✅ Xác nhận", draft, REPORT_CALLBACK_CONFIRM),
                                reportButton("❌ Hủy", draft, REPORT_CALLBACK_CANCEL)
                        )
                );
                default -> List.of(
                        List.of(reportButton("❌ Hủy", draft, REPORT_CALLBACK_CANCEL))
                );
            };
        }
    }

    private InlineKeyboardButton reportButton(String text, ReportDraft draft, String action) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(REPORT_CALLBACK_PREFIX + draft.actionToken + ":" + action);
        return button;
    }

    private String resolveCallbackCommand(String data) {
        if (data == null) {
            return null;
        }
        if (data.startsWith(CALLBACK_COMMAND_PREFIX)) {
            return data.substring(CALLBACK_COMMAND_PREFIX.length()).trim();
        }
        return data.startsWith(REPORT_CALLBACK_PREFIX) ? data : null;
    }

    private String startReportSession(ReportSessionKey sessionKey) {
        if (sessionKey == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        pendingReportSessions.put(sessionKey, new ReportDraft(Instant.now(clock)));
        return REPORT_TITLE_MESSAGE;
    }

    private String cancelReportSession(ReportSessionKey sessionKey) {
        if (sessionKey == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        ReportDraft draft = findActiveReportDraft(sessionKey);
        if (draft == null) {
            return NO_ACTIVE_REPORT_SESSION_MESSAGE;
        }

        return pendingReportSessions.remove(sessionKey, draft)
                ? REPORT_SESSION_CANCELLED_MESSAGE
                : NO_ACTIVE_REPORT_SESSION_MESSAGE;
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

            return formatReportList("5 báo cáo gần đây của bạn:", reports);
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

            return formatReportList("Báo cáo ngày " + reportDate + " của bạn:", reports);
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

    private String findEmployeeInformation(Long telegramUserId) {
        if (telegramUserId == null) {
            return USER_NOT_FOUND_MESSAGE;
        }

        try {
            return userProfileService.findByTelegramUserId(telegramUserId)
                    .map(this::formatCurrentUser)
                    .orElse(USER_NOT_FOUND_MESSAGE);
        } catch (RuntimeException exception) {
            log.error("Cannot get employee information - telegramUserId={}", telegramUserId, exception);
            return EMPLOYEE_INFORMATION_FAILED_MESSAGE;
        }
    }

    private String findTeamStatus(Long telegramUserId, boolean privateChat) {
        if (!privateChat || telegramUserId == null) {
            return TEAM_STATUS_DENIED_MESSAGE;
        }

        try {
            User principal = userProfileService.findByTelegramUserId(telegramUserId).orElse(null);
            if (!canUseManagement(principal)) {
                return TEAM_STATUS_DENIED_MESSAGE;
            }

            TeamReportSummary summary = principal.isAdmin()
                    ? teamReportReadService.readOrganizationSummary(
                    LocalDate.now(clock),
                    clock.instant(),
                    clock.getZone().getId())
                    : teamReportReadService.readTeamSummary(
                    new TeamScope(principal.getDepartmentName(), null),
                    LocalDate.now(clock),
                    clock.instant(),
                    clock.getZone().getId());
            return formatTeamStatus(summary);
        } catch (RuntimeException exception) {
            log.error(
                    "Cannot get Telegram team status - telegramUserId={}, errorType={}",
                    telegramUserId,
                    exception.getClass().getSimpleName()
            );
            return TEAM_STATUS_FAILED_MESSAGE;
        }
    }

    private boolean canUseManagement(User principal) {
        return principal != null
                && principal.getStatus() == UserStatus.ACTIVE
                && (principal.isAdmin()
                || principal.isManager()
                && principal.getDepartmentName() != null
                && !principal.getDepartmentName().isBlank());
    }

    private String manage(Long telegramUserId, boolean privateChat, String text) {
        if (!privateChat || telegramUserId == null) {
            return MANAGEMENT_DENIED_MESSAGE;
        }

        try {
            User principal = userProfileService.findByTelegramUserId(telegramUserId).orElse(null);
            if (!canUseManagement(principal)) {
                return MANAGEMENT_DENIED_MESSAGE;
            }
            String trimmedText = text == null ? "" : text.trim();
            int firstSpace = trimmedText.indexOf(' ');
            if (firstSpace < 0 || trimmedText.substring(firstSpace + 1).isBlank()) {
                return MANAGEMENT_HELP_MESSAGE;
            }

            String arguments = trimmedText.substring(firstSpace + 1).trim();
            int actionEnd = arguments.indexOf(' ');
            String action = (actionEnd < 0 ? arguments : arguments.substring(0, actionEnd))
                    .toLowerCase(Locale.ROOT);
            String payload = actionEnd < 0 ? "" : arguments.substring(actionEnd + 1).trim();
            if (payload.isBlank() && !"users".equals(action)) {
                String usage = managementUsage(action);
                return usage == null ? MANAGEMENT_INVALID_MESSAGE : usage;
            }

            return switch (action) {
                case "users" -> payload.contains("|")
                        ? MANAGEMENT_INVALID_MESSAGE
                        : formatManagementUsers(identityAdministrationService.readUsers(telegramUserId), payload);
                case "user-info" -> manageUserInfo(telegramUserId, payload);
                case "reports" -> manageReports(telegramUserId, payload);
                case "recent-reports" -> manageRecentReports(telegramUserId, payload);
                case "report-info" -> manageReportInfo(telegramUserId, payload);
                case "department-reports" -> manageDepartmentReports(telegramUserId, payload, false);
                case "missing" -> manageDepartmentReports(telegramUserId, payload, true);
                case "organization-summary" -> manageOrganizationReports(telegramUserId, payload);
                case "audit" -> manageAudit(telegramUserId, payload);
                case "user-update", "user-status", "user-department", "report-edit", "report-delete", "role" ->
                        manageMutation(telegramUserId, action, payload);
                default -> MANAGEMENT_INVALID_MESSAGE;
            };
        } catch (IllegalArgumentException exception) {
            return MANAGEMENT_INVALID_MESSAGE;
        } catch (RuntimeException exception) {
            log.error(
                    "Cannot perform Telegram management action - telegramUserId={}, errorType={}",
                    telegramUserId,
                    exception.getClass().getSimpleName()
            );
            return MANAGEMENT_FAILED_MESSAGE;
        }
    }

    private String manageUserInfo(Long actorId, String payload) {
        Long targetId = singleManagementId(payload);
        return targetId == null
                ? MANAGEMENT_INVALID_MESSAGE
                : formatManagementUser(identityAdministrationService.readUser(actorId, targetId));
    }

    private String manageReports(Long actorId, String payload) {
        String[] fields = managementFields(payload, 2);
        Long targetId = fields == null ? null : positiveLong(fields[0]);
        LocalDate reportDate = fields == null ? null : managementDate(fields[1]);
        return targetId == null || reportDate == null
                ? MANAGEMENT_INVALID_MESSAGE
                : formatManagementReportList(
                        identityAdministrationService.readReports(actorId, targetId, reportDate),
                        "Báo cáo ngày " + reportDate
                );
    }

    private String manageRecentReports(Long actorId, String payload) {
        Long targetId = singleManagementId(payload);
        return targetId == null
                ? MANAGEMENT_INVALID_MESSAGE
                : formatManagementReportList(
                        identityAdministrationService.readRecentReports(actorId, targetId),
                        "Báo cáo gần nhất"
                );
    }

    private String manageReportInfo(Long actorId, String payload) {
        Long reportId = singleManagementId(payload);
        return reportId == null
                ? MANAGEMENT_INVALID_MESSAGE
                : formatManagementReport(identityAdministrationService.readReport(actorId, reportId));
    }

    private String manageDepartmentReports(Long actorId, String payload, boolean missingOnly) {
        String[] fields = managementFields(payload, 2);
        LocalDate reportDate = fields == null ? null : managementDate(fields[1]);
        if (fields == null || fields[0].isBlank() || fields[0].length() > 255 || reportDate == null) {
            return MANAGEMENT_INVALID_MESSAGE;
        }
        ManagementReadResult result = identityAdministrationService.readDepartmentReports(
                actorId,
                fields[0],
                reportDate
        );
        return missingOnly
                ? formatMissingUsers(result, fields[0], reportDate)
                : formatDepartmentReports(result, fields[0], reportDate);
    }

    private String manageOrganizationReports(Long actorId, String payload) {
        LocalDate reportDate = managementDate(payload);
        return reportDate == null
                ? MANAGEMENT_INVALID_MESSAGE
                : formatOrganizationReports(
                        identityAdministrationService.readOrganizationReports(actorId, reportDate),
                        reportDate
                );
    }

    private String manageAudit(Long actorId, String payload) {
        Long targetId = singleManagementId(payload);
        return targetId == null
                ? MANAGEMENT_INVALID_MESSAGE
                : formatManagementAudit(identityAdministrationService.readAudit(actorId, targetId));
    }

    private String manageMutation(Long actorId, String action, String payload) {
        String[] fields;
        Long targetId;
        IdentityAdminResult result;
        switch (action) {
            case "user-update":
                fields = managementFields(payload, 5);
                targetId = fields == null ? null : positiveLong(fields[0]);
                result = targetId != null
                        && validManagementProfile(fields[1], fields[2], fields[3])
                        && validManagementReason(fields[4])
                        ? identityAdministrationService.updateUser(
                                actorId, targetId, fields[1], fields[2], fields[3], fields[4]
                        )
                        : null;
                break;
            case "user-status":
                fields = managementFields(payload, 3);
                targetId = fields == null ? null : positiveLong(fields[0]);
                result = targetId != null && validManagementReason(fields[2])
                        ? identityAdministrationService.changeStatus(
                                actorId,
                                targetId,
                                UserStatus.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                                fields[2]
                        )
                        : null;
                break;
            case "user-department":
                fields = managementFields(payload, 4);
                targetId = fields == null ? null : positiveLong(fields[0]);
                result = targetId != null
                        && !fields[1].isBlank()
                        && fields[1].length() <= 255
                        && fields[2].length() <= 255
                        && validManagementReason(fields[3])
                        ? identityAdministrationService.changeDepartment(
                                actorId, targetId, fields[1], fields[2], fields[3]
                        )
                        : null;
                break;
            case "report-edit":
                fields = managementFields(payload, 3);
                targetId = fields == null ? null : positiveLong(fields[0]);
                result = targetId != null && !fields[1].isBlank() && validManagementReason(fields[2])
                        ? identityAdministrationService.editReport(actorId, targetId, fields[1], fields[2])
                        : null;
                break;
            case "report-delete":
                fields = managementFields(payload, 2);
                targetId = fields == null ? null : positiveLong(fields[0]);
                result = targetId != null && validManagementReason(fields[1])
                        ? identityAdministrationService.deleteReport(actorId, targetId, fields[1])
                        : null;
                break;
            case "role":
                fields = managementFields(payload, 4);
                targetId = fields == null ? null : positiveLong(fields[0]);
                result = targetId != null && validManagementReason(fields[3])
                        ? identityAdministrationService.changeRole(
                                actorId,
                                targetId,
                                IdentityAdministrationService.Role.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                                IdentityAdministrationService.RoleChange.valueOf(fields[2].toUpperCase(Locale.ROOT)),
                                fields[3]
                        )
                        : null;
                break;
            default:
                result = null;
        }
        return result == null ? MANAGEMENT_INVALID_MESSAGE : managementMessage(result.status());
    }

    private String managementUsage(String action) {
        return switch (action) {
            case "user-info" -> "Cú pháp: /manage user-info <telegramUserId>";
            case "reports" -> "Cú pháp: /manage reports <telegramUserId>|<YYYY-MM-DD>";
            case "recent-reports" -> "Cú pháp: /manage recent-reports <telegramUserId>";
            case "report-info" -> "Cú pháp: /manage report-info <reportId>";
            case "department-reports" -> "Cú pháp: /manage department-reports <departmentName>|<YYYY-MM-DD>";
            case "missing" -> "Cú pháp: /manage missing <departmentName>|<YYYY-MM-DD>";
            case "organization-summary" -> "Cú pháp: /manage organization-summary <YYYY-MM-DD>";
            case "audit" -> "Cú pháp: /manage audit <telegramUserId>";
            case "user-update" -> "Cú pháp: /manage user-update <telegramUserId>|<employeeCode>|<fullName>|<unitName>|<reason>";
            case "user-status" -> "Cú pháp: /manage user-status <telegramUserId>|<ACTIVE_OR_INACTIVE>|<reason>";
            case "user-department" -> "Cú pháp: /manage user-department <telegramUserId>|<departmentName>|<unitName>|<reason>";
            case "report-edit" -> "Cú pháp: /manage report-edit <reportId>|<newContent>|<reason>";
            case "report-delete" -> "Cú pháp: /manage report-delete <reportId>|<reason>";
            case "role" -> "Cú pháp: /manage role <telegramUserId>|<MANAGER_OR_ADMIN>|<GRANT_OR_REVOKE>|<reason>";
            default -> null;
        };
    }

    private String[] managementFields(String payload, int expectedCount) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length != expectedCount) {
            return null;
        }
        for (int index = 0; index < fields.length; index += 1) {
            fields[index] = fields[index].trim();
        }
        return fields;
    }

    private Long singleManagementId(String payload) {
        return payload.contains("|") || payload.chars().anyMatch(Character::isWhitespace)
                ? null
                : positiveLong(payload);
    }

    private LocalDate managementDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private Long positiveLong(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean validManagementProfile(String employeeCode, String fullName, String unitName) {
        return employeeCode.length() <= 255 && fullName.length() <= 255 && unitName.length() <= 255;
    }

    private boolean validManagementReason(String reason) {
        return !reason.isBlank() && reason.length() <= 1000;
    }

    private String managementMessage(IdentityAdminStatus status) {
        if (status == null) {
            return MANAGEMENT_FAILED_MESSAGE;
        }
        return switch (status) {
            case SUCCESS, CREATED, UPDATED, REMAPPED, DEACTIVATED, REACTIVATED -> MANAGEMENT_SUCCESS_MESSAGE;
            case DELETED -> MANAGEMENT_DELETED_MESSAGE;
            case NO_DATA -> MANAGEMENT_NO_DATA_MESSAGE;
            case NO_CHANGE -> MANAGEMENT_NO_CHANGE_MESSAGE;
            case ACCESS_DENIED -> MANAGEMENT_DENIED_MESSAGE;
            case USER_NOT_FOUND, REPORT_NOT_FOUND -> MANAGEMENT_NOT_FOUND_MESSAGE;
            case IDENTITY_CONFLICT -> MANAGEMENT_CONFLICT_MESSAGE;
            case INVALID_REQUEST -> MANAGEMENT_INVALID_MESSAGE;
            case FAILED -> MANAGEMENT_FAILED_MESSAGE;
        };
    }

    private String formatManagementUsers(ManagementReadResult result, String keyword) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }

        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Long numericKeyword = normalizedKeyword.matches("[0-9]+") ? positiveLong(normalizedKeyword) : null;
        List<User> users = new ArrayList<>();
        for (User user : result.users()) {
            if (normalizedKeyword.isBlank()
                    || containsIgnoreCase(user.getEmployeeCode(), normalizedKeyword)
                    || containsIgnoreCase(user.getFullName(), normalizedKeyword)
                    || numericKeyword != null && numericKeyword.equals(user.getTelegramUserId())) {
                users.add(user);
            }
        }
        users.sort(managementUserComparator());
        if (users.isEmpty()) {
            return MANAGEMENT_NO_DATA_MESSAGE;
        }

        int eligible = Math.min(users.size(), MANAGEMENT_USER_LIMIT);
        int shown = 0;
        StringBuilder message = new StringBuilder("Danh sách người dùng:");
        for (int index = 0; index < eligible; index += 1) {
            String block = "\n\n" + (index + 1) + ". " + formatManagementUserBlock(users.get(index));
            String marker = users.size() > index + 1
                    ? "\n\nCòn " + (users.size() - index - 1) + " người dùng chưa hiển thị."
                    : "";
            if (message.length() + block.length() + marker.length() > MANAGEMENT_MESSAGE_BUDGET) {
                break;
            }
            message.append(block);
            shown += 1;
        }
        if (users.size() > shown) {
            message.append("\n\nCòn ").append(users.size() - shown).append(" người dùng chưa hiển thị.");
        }
        return message.toString();
    }

    private String formatManagementUser(ManagementReadResult result) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }
        return result.users().isEmpty()
                ? MANAGEMENT_NO_DATA_MESSAGE
                : "Thông tin người dùng:\n" + formatManagementUserBlock(result.users().get(0));
    }

    private String formatManagementUserBlock(User user) {
        StringBuilder message = new StringBuilder();
        message.append(safeLabel(user.getEmployeeCode())).append(" — ").append(safeLabel(user.getFullName()));
        message.append("\nTelegram user ID: ").append(user.getTelegramUserId());
        message.append("\nPhòng ban: ").append(safeLabel(user.getDepartmentName()));
        message.append("\nĐơn vị: ").append(safeLabel(user.getUnitName()));
        message.append("\nTrạng thái: ").append(user.getStatus() == null ? "—" : user.getStatus().name());
        message.append("\nVai trò: ");
        if (!user.isManager() && !user.isAdmin()) {
            message.append("USER");
        } else {
            if (user.isManager()) {
                message.append("MANAGER");
            }
            if (user.isAdmin()) {
                if (user.isManager()) {
                    message.append(", ");
                }
                message.append("ADMIN");
            }
        }
        return message.toString();
    }

    private String formatManagementReportList(ManagementReadResult result, String heading) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }
        if (result.reports().isEmpty()) {
            return MANAGEMENT_NO_DATA_MESSAGE;
        }

        int eligible = Math.min(result.reports().size(), RECENT_REPORTS_LIMIT);
        int shown = 0;
        StringBuilder message = new StringBuilder(heading).append(":");
        for (int index = 0; index < eligible; index += 1) {
            String block = "\n\n" + (index + 1) + ". "
                    + formatManagementReportListItem(result.reports().get(index));
            int remainingAfter = result.reports().size() - index - 1;
            String marker = remainingAfter > 0
                    ? "\n\nCòn " + remainingAfter + " báo cáo chưa hiển thị."
                    : "";
            if (message.length() + block.length() + marker.length() > MANAGEMENT_MESSAGE_BUDGET) {
                break;
            }
            message.append(block);
            shown += 1;
        }
        if (result.reports().size() > shown) {
            message.append("\n\nCòn ").append(result.reports().size() - shown)
                    .append(" báo cáo chưa hiển thị.");
        }
        return message.toString();
    }

    private String formatManagementReportListItem(DailyReport report) {
        StringBuilder message = new StringBuilder("Report ID: ").append(report.getId());
        message.append("\nNgày báo cáo: ").append(report.getReportDate());
        message.append("\nThời gian lưu: ").append(formatManagementTime(report.getCreatedAt()));
        message.append("\nPhòng lúc nộp: ").append(safeLabel(report.getDepartment()));
        message.append("\nĐơn vị lúc nộp: ").append(safeLabel(report.getUnit()));
        message.append("\nNội dung: ").append(createContentPreview(report.getContent()));
        return message.toString();
    }

    private String formatManagementReport(ManagementReadResult result) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }
        if (result.reports().isEmpty()) {
            return MANAGEMENT_NO_DATA_MESSAGE;
        }

        DailyReport report = result.reports().get(0);
        User owner = result.users().isEmpty() ? report.getUser() : result.users().get(0);
        StringBuilder message = new StringBuilder("Chi tiết báo cáo:");
        message.append("\nReport ID: ").append(report.getId());
        if (owner != null) {
            message.append("\nNgười nộp: ").append(safeLabel(owner.getEmployeeCode()))
                    .append(" — ").append(safeLabel(owner.getFullName()));
        }
        message.append("\nNgày báo cáo: ").append(report.getReportDate());
        message.append("\nThời gian lưu: ").append(formatManagementTime(report.getCreatedAt()));
        message.append("\nPhòng lúc nộp: ").append(safeLabel(report.getDepartment()));
        message.append("\nĐơn vị lúc nộp: ").append(safeLabel(report.getUnit()));
        message.append("\nNội dung:\n");
        appendWithinBudget(message, report.getContent(), "\n… [Nội dung đã được rút gọn]");
        return message.toString();
    }

    private String formatDepartmentReports(
            ManagementReadResult result,
            String department,
            LocalDate reportDate
    ) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }

        List<User> users = new ArrayList<>(result.users());
        users.sort(managementUserComparator());
        Map<Long, List<DailyReport>> reportsByUser = managementReportsByUser(result.reports());
        int submitted = 0;
        for (User user : users) {
            if (!reportsByUser.getOrDefault(user.getId(), List.of()).isEmpty()) {
                submitted += 1;
            }
        }

        StringBuilder message = new StringBuilder("Báo cáo phòng: ").append(safeLabel(department));
        message.append("\nNgày: ").append(reportDate);
        message.append("\nTổng người dùng: ").append(users.size());
        message.append("\nĐã nộp: ").append(submitted);
        message.append("\nChưa nộp: ").append(users.size() - submitted);
        message.append("\nTổng báo cáo: ").append(result.reports().size());

        int includedReports = 0;
        int nextUser = 0;
        for (; nextUser < users.size(); nextUser += 1) {
            User user = users.get(nextUser);
            List<DailyReport> reports = reportsByUser.getOrDefault(user.getId(), List.of());
            String block = formatDepartmentUser(user, reports);
            int reportsAfter = result.reports().size() - includedReports - reports.size();
            String markerAfter = departmentOmission(users.size() - nextUser - 1, reportsAfter);
            if (message.length() + block.length() + markerAfter.length() > MANAGEMENT_MESSAGE_BUDGET) {
                break;
            }
            message.append(block);
            includedReports += reports.size();
        }
        message.append(departmentOmission(
                users.size() - nextUser,
                result.reports().size() - includedReports
        ));
        return message.toString();
    }

    private String formatDepartmentUser(User user, List<DailyReport> reports) {
        StringBuilder block = new StringBuilder("\n\n- ")
                .append(safeLabel(user.getEmployeeCode())).append(" — ").append(safeLabel(user.getFullName()));
        block.append("\n  Trạng thái nộp: ").append(reports.isEmpty() ? "CHƯA NỘP" : "ĐÃ NỘP");
        block.append("\n  Số báo cáo: ").append(reports.size());
        for (DailyReport report : reports) {
            block.append("\n  • ID ").append(report.getId())
                    .append(" | ").append(formatManagementTime(report.getCreatedAt()))
                    .append(" | ").append(createContentPreview(report.getContent()));
        }
        return block.toString();
    }

    private String departmentOmission(int users, int reports) {
        return users <= 0 && reports <= 0
                ? ""
                : "\n\nĐã lược bỏ " + Math.max(users, 0) + " người dùng và "
                + Math.max(reports, 0) + " báo cáo.";
    }

    private String formatMissingUsers(
            ManagementReadResult result,
            String department,
            LocalDate reportDate
    ) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }

        Map<Long, List<DailyReport>> reportsByUser = managementReportsByUser(result.reports());
        List<User> missing = new ArrayList<>();
        for (User user : result.users()) {
            if (reportsByUser.getOrDefault(user.getId(), List.of()).isEmpty()) {
                missing.add(user);
            }
        }
        missing.sort(managementUserComparator());
        StringBuilder message = new StringBuilder("Chưa nộp — phòng: ").append(safeLabel(department));
        message.append("\nNgày: ").append(reportDate);
        message.append("\nTổng người dùng: ").append(result.users().size());
        message.append("\nĐã nộp: ").append(result.users().size() - missing.size());
        message.append("\nChưa nộp: ").append(missing.size());
        message.append("\nTổng báo cáo: ").append(result.reports().size());
        int shown = 0;
        for (User user : missing) {
            String block = "\n\n- " + safeLabel(user.getEmployeeCode()) + " — "
                    + safeLabel(user.getFullName()) + " | " + safeLabel(user.getUnitName());
            String marker = shown + 1 < missing.size()
                    ? "\n\nĐã lược bỏ " + (missing.size() - shown - 1) + " người chưa nộp."
                    : "";
            if (message.length() + block.length() + marker.length() > MANAGEMENT_MESSAGE_BUDGET) {
                break;
            }
            message.append(block);
            shown += 1;
        }
        if (shown < missing.size()) {
            message.append("\n\nĐã lược bỏ ").append(missing.size() - shown).append(" người chưa nộp.");
        }
        return message.toString();
    }

    private String formatOrganizationReports(ManagementReadResult result, LocalDate reportDate) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }

        Map<Long, List<DailyReport>> reportsByUser = managementReportsByUser(result.reports());
        Map<String, int[]> departments = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int submitted = 0;
        for (User user : result.users()) {
            String department = user.getDepartmentName() == null || user.getDepartmentName().isBlank()
                    ? "Chưa phân phòng"
                    : user.getDepartmentName().trim();
            int[] counts = departments.computeIfAbsent(department, ignored -> new int[4]);
            List<DailyReport> reports = reportsByUser.getOrDefault(user.getId(), List.of());
            counts[0] += 1;
            counts[3] += reports.size();
            if (reports.isEmpty()) {
                counts[2] += 1;
            } else {
                counts[1] += 1;
                submitted += 1;
            }
        }

        StringBuilder message = new StringBuilder("Tổng hợp tổ chức ngày ").append(reportDate);
        message.append("\nTổng người dùng: ").append(result.users().size());
        message.append("\nĐã nộp: ").append(submitted);
        message.append("\nChưa nộp: ").append(result.users().size() - submitted);
        message.append("\nTổng báo cáo: ").append(result.reports().size());
        int shown = 0;
        for (Map.Entry<String, int[]> entry : departments.entrySet()) {
            int[] counts = entry.getValue();
            String block = "\n\n- " + safeLabel(entry.getKey())
                    + ": tổng " + counts[0]
                    + ", đã nộp " + counts[1]
                    + ", chưa nộp " + counts[2]
                    + ", báo cáo " + counts[3];
            int remainingAfter = departments.size() - shown - 1;
            String marker = remainingAfter > 0
                    ? "\n\nĐã lược bỏ " + remainingAfter + " phòng ban."
                    : "";
            if (message.length() + block.length() + marker.length() > MANAGEMENT_MESSAGE_BUDGET) {
                break;
            }
            message.append(block);
            shown += 1;
        }
        if (shown < departments.size()) {
            message.append("\n\nĐã lược bỏ ").append(departments.size() - shown).append(" phòng ban.");
        }
        return message.toString();
    }

    private String formatManagementAudit(ManagementReadResult result) {
        String failure = managementReadFailure(result);
        if (failure != null) {
            return failure;
        }
        if (result.auditEvents().isEmpty()) {
            return MANAGEMENT_NO_DATA_MESSAGE;
        }

        int eligible = Math.min(result.auditEvents().size(), MANAGEMENT_AUDIT_LIMIT);
        int shown = 0;
        StringBuilder message = new StringBuilder("Lịch sử quản trị:");
        for (int index = 0; index < eligible; index += 1) {
            IdentityAuditEvent event = result.auditEvents().get(index);
            String block = new StringBuilder("\n\n").append(index + 1).append(". ")
                    .append(formatManagementTime(event.getCreatedAt()))
                    .append(" | ").append(event.getAction())
                    .append("\nActor: ").append(safeLabel(event.getActor()))
                    .append("\nLý do: ").append(preview(event.getReason(), MANAGEMENT_AUDIT_REASON_LIMIT))
                    .toString();
            int remainingAfter = result.auditEvents().size() - index - 1;
            String marker = remainingAfter > 0
                    ? "\n\nCòn " + remainingAfter + " sự kiện chưa hiển thị."
                    : "";
            if (message.length() + block.length() + marker.length() > MANAGEMENT_MESSAGE_BUDGET) {
                break;
            }
            message.append(block);
            shown += 1;
        }
        if (result.auditEvents().size() > shown) {
            message.append("\n\nCòn ").append(result.auditEvents().size() - shown)
                    .append(" sự kiện chưa hiển thị.");
        }
        return message.toString();
    }

    private String managementReadFailure(ManagementReadResult result) {
        if (result == null) {
            return MANAGEMENT_FAILED_MESSAGE;
        }
        return result.status() == IdentityAdminStatus.SUCCESS ? null : managementMessage(result.status());
    }

    private Map<Long, List<DailyReport>> managementReportsByUser(List<DailyReport> reports) {
        Map<Long, List<DailyReport>> grouped = new HashMap<>();
        for (DailyReport report : reports) {
            if (report.getUser() != null && report.getUser().getId() != null) {
                grouped.computeIfAbsent(report.getUser().getId(), ignored -> new ArrayList<>()).add(report);
            }
        }
        Comparator<DailyReport> newestFirst = Comparator
                .comparing(DailyReport::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DailyReport::getId, Comparator.nullsLast(Comparator.reverseOrder()));
        grouped.values().forEach(values -> values.sort(newestFirst));
        return grouped;
    }

    private Comparator<User> managementUserComparator() {
        return Comparator.comparing(
                        (User user) -> safeLabel(user.getEmployeeCode()),
                        String.CASE_INSENSITIVE_ORDER
                )
                .thenComparing(user -> safeLabel(user.getFullName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(User::getTelegramUserId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String formatManagementTime(LocalDateTime value) {
        return value == null ? "—" : value.format(REPORT_CREATED_AT_FORMATTER);
    }

    private String preview(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= limit
                ? normalized
                : normalized.substring(0, limit).stripTrailing() + "...";
    }

    private void appendWithinBudget(StringBuilder message, String value, String truncationMarker) {
        String content = value == null ? "" : value.strip();
        if (message.length() + content.length() <= MANAGEMENT_MESSAGE_BUDGET) {
            message.append(content);
            return;
        }
        int available = Math.max(0, MANAGEMENT_MESSAGE_BUDGET - message.length() - truncationMarker.length());
        message.append(content, 0, Math.min(content.length(), available)).append(truncationMarker);
    }

    private String formatTeamStatus(TeamReportSummary summary) {
        StringBuilder message = new StringBuilder("Trạng thái đội nhóm ngày ")
                .append(summary.businessDate())
                .append("\nPhạm vi: ")
                .append(summary.scope().departmentName());
        if (summary.scope().unitName() != null) {
            message.append(" / ").append(summary.scope().unitName());
        }
        message.append("\nTổng thành viên: ").append(summary.totalEmployees());
        for (TeamReportStatus status : TeamReportStatus.values()) {
            message.append("\n").append(status).append(": ").append(summary.count(status));
        }
        for (TeamMemberReportStatus member : summary.members()) {
            message.append("\n\n- ").append(member.displayName()).append(": ").append(member.status());
        }
        return message.toString();
    }

    private String formatCurrentUser(User user) {
        StringBuilder message = new StringBuilder("Thông tin nhân viên:");
        message.append("\nTelegram user ID: ").append(user.getTelegramUserId());
        appendIfPresent(message, "Mã nhân viên", user.getEmployeeCode());
        appendIfPresent(message, "Họ tên", user.getFullName());
        appendIfPresent(message, "Phòng ban", user.getDepartmentName());
        appendIfPresent(message, "Đơn vị", user.getUnitName());
        if (user.getStatus() != null) {
            message.append("\nTrạng thái: ").append(formatUserStatus(user));
        }
        return message.toString();
    }

    private String formatUserStatus(User user) {
        return switch (user.getStatus()) {
            case ACTIVE -> "Đang hoạt động";
            case INACTIVE -> "Ngừng hoạt động";
        };
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append("\n").append(label).append(": ").append(value);
        }
    }

    private String formatTodayReportStatus(LocalDate reportDate, List<DailyReport> reports) {
        StringBuilder message = new StringBuilder("Trạng thái báo cáo hôm nay (")
                .append(reportDate)
                .append("):");
        message.append("\nĐã có ").append(reports.size()).append(" báo cáo.");

        DailyReport latestReport = reports.get(0);
        if (latestReport.getCreatedAt() != null) {
            message.append("\nLần mới nhất lưu lúc: ")
                    .append(latestReport.getCreatedAt().format(REPORT_CREATED_AT_FORMATTER));
        }
        message.append("\nNội dung mới nhất:\n").append(createContentPreview(latestReport.getContent()));
        return message.toString();
    }

    private String formatReportList(String heading, List<DailyReport> reports) {
        StringBuilder message = new StringBuilder(heading);
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
                        .append(report.getCreatedAt().format(REPORT_CREATED_AT_FORMATTER));
            }
            message.append("\nNội dung:\n").append(createContentPreview(report.getContent()));
        }
        return message.toString();
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

    private String handleReportInput(ReportSessionKey sessionKey, Long telegramUserId, String text) {
        ReportDraft draft = findActiveReportDraft(sessionKey);
        if (draft == null) {
            return REPORT_REQUIRED_MESSAGE;
        }

        if (telegramUserId == null) {
            clearReportSession(sessionKey);
            return USER_NOT_FOUND_MESSAGE;
        }

        synchronized (draft) {
            if (pendingReportSessions.get(sessionKey) != draft || isExpired(draft.startedAt)) {
                pendingReportSessions.remove(sessionKey, draft);
                return REPORT_REQUIRED_MESSAGE;
            }

            return switch (draft.step) {
                case TITLE, EDIT_TITLE -> acceptTitle(draft, text);
                case COLLABORATORS, EDIT_COLLABORATORS -> acceptCollaborators(draft, text);
                case CONTENT, EDIT_CONTENT -> acceptContent(sessionKey, telegramUserId, draft, text);
                case PREVIEW -> REPORT_PREVIEW_INPUT_MESSAGE;
            };
        }
    }

    private String acceptTitle(ReportDraft draft, String text) {
        try {
            draft.title = ReportSubmissionDetails.normalizeTitle(text);
        } catch (ReportSubmissionDetails.ValidationException exception) {
            return exception.tooLong() ? LONG_TITLE_MESSAGE : BLANK_TITLE_MESSAGE;
        }
        if (draft.step == ReportStep.EDIT_TITLE) {
            draft.moveTo(ReportStep.PREVIEW);
            return formatReportDetails(draft);
        }

        draft.moveTo(ReportStep.COLLABORATORS);
        return REPORT_COLLABORATORS_MESSAGE;
    }

    private String acceptCollaborators(ReportDraft draft, String text) {
        try {
            draft.collaborators = ReportSubmissionDetails.normalizeCollaborators(text);
        } catch (ReportSubmissionDetails.ValidationException exception) {
            return exception.tooLong() ? LONG_COLLABORATORS_MESSAGE : BLANK_COLLABORATORS_MESSAGE;
        }
        if (draft.step == ReportStep.EDIT_COLLABORATORS) {
            draft.moveTo(ReportStep.PREVIEW);
            return formatReportDetails(draft);
        }

        draft.moveTo(ReportStep.CONTENT);
        return REPORT_CONTENT_MESSAGE;
    }

    private String acceptContent(
            ReportSessionKey sessionKey,
            Long telegramUserId,
            ReportDraft draft,
            String text
    ) {
        try {
            draft.content = ReportSubmissionDetails.normalizeContent(text);
        } catch (ReportSubmissionDetails.ValidationException exception) {
            return exception.tooLong() ? LONG_REPORT_CONTENT_MESSAGE : BLANK_REPORT_MESSAGE;
        }
        if (draft.primaryPerformer == null) {
            try {
                User user = userProfileService.findByTelegramUserId(telegramUserId).orElse(null);
                if (user == null) {
                    pendingReportSessions.remove(sessionKey, draft);
                    return USER_NOT_FOUND_MESSAGE;
                }
                draft.ownerId = user.getId();
                draft.primaryPerformer = ReportSubmissionDetails.primaryPerformer(user, telegramUserId);
            } catch (RuntimeException exception) {
                log.error(
                        "Cannot resolve report performer - telegramUserId={}, errorType={}",
                        telegramUserId,
                        exception.getClass().getSimpleName()
                );
                pendingReportSessions.remove(sessionKey, draft);
                return USER_NOT_FOUND_MESSAGE;
            }
        }

        draft.moveTo(ReportStep.PREVIEW);
        return formatReportDetails(draft);
    }

    private String handleReportCallback(
            ReportSessionKey sessionKey,
            Long telegramUserId,
            String callbackData
    ) {
        int actionSeparator = callbackData.lastIndexOf(':');
        if (sessionKey == null || actionSeparator <= REPORT_CALLBACK_PREFIX.length()) {
            return REPORT_ACTION_EXPIRED_MESSAGE;
        }

        String token = callbackData.substring(REPORT_CALLBACK_PREFIX.length(), actionSeparator);
        String action = callbackData.substring(actionSeparator + 1);
        ReportDraft draft = findActiveReportDraft(sessionKey);
        if (draft == null) {
            return REPORT_ACTION_EXPIRED_MESSAGE;
        }

        synchronized (draft) {
            if (pendingReportSessions.get(sessionKey) != draft
                    || isExpired(draft.startedAt)
                    || !draft.actionToken.equals(token)) {
                return REPORT_ACTION_EXPIRED_MESSAGE;
            }

            return switch (action) {
                case REPORT_CALLBACK_SOLO -> selectSoloWork(draft);
                case REPORT_CALLBACK_EDIT_TITLE -> startReportEdit(
                        draft,
                        ReportStep.EDIT_TITLE,
                        REPORT_TITLE_MESSAGE
                );
                case REPORT_CALLBACK_EDIT_COLLABORATORS -> startReportEdit(
                        draft,
                        ReportStep.EDIT_COLLABORATORS,
                        REPORT_COLLABORATORS_MESSAGE
                );
                case REPORT_CALLBACK_EDIT_CONTENT -> startReportEdit(
                        draft,
                        ReportStep.EDIT_CONTENT,
                        REPORT_CONTENT_MESSAGE
                );
                case REPORT_CALLBACK_CONFIRM -> confirmReport(sessionKey, telegramUserId, draft);
                case REPORT_CALLBACK_CANCEL -> {
                    pendingReportSessions.remove(sessionKey, draft);
                    yield REPORT_SESSION_CANCELLED_MESSAGE;
                }
                default -> REPORT_ACTION_EXPIRED_MESSAGE;
            };
        }
    }

    private String startReportEdit(ReportDraft draft, ReportStep editStep, String prompt) {
        if (draft.step != ReportStep.PREVIEW) {
            return REPORT_ACTION_EXPIRED_MESSAGE;
        }

        draft.moveTo(editStep);
        return prompt;
    }

    private String selectSoloWork(ReportDraft draft) {
        if (draft.step != ReportStep.COLLABORATORS
                && draft.step != ReportStep.EDIT_COLLABORATORS) {
            return REPORT_ACTION_EXPIRED_MESSAGE;
        }

        draft.collaborators = ReportSubmissionDetails.SOLO_COLLABORATORS;
        if (draft.step == ReportStep.EDIT_COLLABORATORS) {
            draft.moveTo(ReportStep.PREVIEW);
            return formatReportDetails(draft);
        }

        draft.moveTo(ReportStep.CONTENT);
        return REPORT_CONTENT_MESSAGE;
    }

    private String confirmReport(
            ReportSessionKey sessionKey,
            Long telegramUserId,
            ReportDraft draft
    ) {
        if (draft.step != ReportStep.PREVIEW || !pendingReportSessions.remove(sessionKey, draft)) {
            return REPORT_ACTION_EXPIRED_MESSAGE;
        }

        DailyReportSubmissionStatus status;
        Instant submissionInstant = Instant.now(clock);
        try {
            status = dailyReportService.submitToday(
                    telegramUserId,
                    draft.ownerId,
                    serializeReport(draft),
                    draft.collaborators,
                    submissionInstant
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Cannot save Telegram report - telegramUserId={}, errorType={}",
                    telegramUserId,
                    exception.getClass().getSimpleName()
            );
            return REPORT_SAVE_FAILED_MESSAGE;
        }

        return switch (status) {
            case SAVED -> REPORT_SAVED_MESSAGE + "\n\n" + formatReportDetails(draft, submissionInstant);
            case BLANK_CONTENT -> BLANK_REPORT_MESSAGE;
            case USER_INACTIVE -> INACTIVE_REPORT_MESSAGE;
            case TELEGRAM_USER_NOT_FOUND -> USER_NOT_FOUND_MESSAGE;
            case IDENTITY_CHANGED -> REPORT_IDENTITY_CHANGED_MESSAGE;
        };
    }

    private String formatReportDetails(ReportDraft draft) {
        return formatReportDetails(draft, Instant.now(clock));
    }

    private String formatReportDetails(ReportDraft draft, Instant displayedAt) {
        return "📋 CHI TIẾT BÁO CÁO"
                + "\n\nGiờ gửi: " + LocalDateTime.ofInstant(displayedAt, clock.getZone())
                        .format(REPORT_CREATED_AT_FORMATTER)
                + "\n" + serializeReport(draft);
    }

    private String serializeReport(ReportDraft draft) {
        return new ReportSubmissionDetails(draft.title, draft.collaborators, draft.content)
                .serialize(draft.primaryPerformer);
    }

    private void clearReportSession(ReportSessionKey sessionKey) {
        if (sessionKey != null) pendingReportSessions.remove(sessionKey);
    }

    private ReportDraft findActiveReportDraft(ReportSessionKey sessionKey) {
        if (sessionKey == null) {
            return null;
        }

        ReportDraft draft = pendingReportSessions.get(sessionKey);
        if (draft == null) {
            return null;
        }

        if (isExpired(draft.startedAt)) {
            pendingReportSessions.remove(sessionKey, draft);
            return null;
        }

        return draft;
    }

    private void removeExpiredReportSessions() {
        pendingReportSessions.entrySet()
                .removeIf(entry -> isExpired(entry.getValue().startedAt));
    }

    private boolean removeExpiredReportSession(ReportSessionKey sessionKey) {
        if (sessionKey == null) {
            return false;
        }

        ReportDraft draft = pendingReportSessions.get(sessionKey);
        return draft != null
                && isExpired(draft.startedAt)
                && pendingReportSessions.remove(sessionKey, draft);
    }

    private boolean isExpired(Instant startedAt) {
        return !Instant.now(clock).isBefore(startedAt.plus(REPORT_SESSION_TIMEOUT));
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
        org.telegram.telegrambots.meta.api.objects.User user = message.getFrom();
        return user != null ? user.getId() : null;
    }

    private ReportSessionKey resolveSessionKey(Long chatId, Long telegramUserId) {
        return chatId == null || telegramUserId == null ? null : new ReportSessionKey(chatId, telegramUserId);
    }

    private enum ReportStep {
        TITLE,
        COLLABORATORS,
        CONTENT,
        PREVIEW,
        EDIT_TITLE,
        EDIT_COLLABORATORS,
        EDIT_CONTENT
    }

    private static final class ReportDraft {
        private final Instant startedAt;
        private String actionToken = UUID.randomUUID().toString();
        private ReportStep step = ReportStep.TITLE;
        private String title;
        private Long ownerId;
        private String primaryPerformer;
        private String collaborators;
        private String content;

        private ReportDraft(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private void moveTo(ReportStep nextStep) {
            step = nextStep;
            actionToken = UUID.randomUUID().toString();
        }
    }

    private record ReportSessionKey(Long chatId, Long telegramUserId) {}

    private record CommandRequest(
            Long chatId,
            Long telegramUserId,
            String text,
            boolean reportSessionExpired,
            boolean privateChat
    ) {}
}
