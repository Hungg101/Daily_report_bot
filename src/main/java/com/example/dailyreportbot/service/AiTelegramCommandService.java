package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.Clock;
import java.util.Optional;

/**
 * AI-enhanced command service that intercepts unrecognized-input responses
 * and replaces them with Gemini AI responses.
 *
 * <p>Annotated {@code @Primary} so Spring injects this instead of the base
 * {@link TelegramCommandService} wherever that type is required (e.g. in
 * {@link com.example.dailyreportbot.bot.DailyReportBot DailyReportBot}).</p>
 *
 * <p>All existing commands pass through unchanged. Only the two sentinel
 * error messages are intercepted:</p>
 * <ul>
 *   <li>"Lệnh không được hỗ trợ" — unsupported command</li>
 *   <li>"Vui lòng gửi /report trước khi nhập báo cáo mới." — free text outside report session</li>
 * </ul>
 *
 * <p>If the AI service is disabled or fails, the original response is returned.</p>
 */
@Service
@Primary
public class AiTelegramCommandService extends TelegramCommandService {

    private static final Logger log = LoggerFactory.getLogger(AiTelegramCommandService.class);

    private static final String UNSUPPORTED_COMMAND_PREFIX = "Lệnh không được hỗ trợ";
    private static final String REPORT_REQUIRED_TEXT =
            "Vui lòng gửi /report trước khi nhập báo cáo mới.";

    private final GeminiAiService geminiAiService;
    private final UserProfileService userProfileService;
    private final Clock clock;

    public AiTelegramCommandService(
            DailyReportService dailyReportService,
            UserProfileService userProfileService,
            TeamReportReadService teamReportReadService,
            IdentityAdministrationService identityAdministrationService,
            Clock clock,
            TelegramBotProperties botProperties,
            GeminiAiService geminiAiService
    ) {
        super(
                dailyReportService,
                userProfileService,
                teamReportReadService,
                identityAdministrationService,
                clock,
                botProperties
        );
        this.geminiAiService = geminiAiService;
        this.userProfileService = userProfileService;
        this.clock = clock;
    }

    @Override
    public Optional<SendMessage> createResponse(Message message) {
        if (message == null || message.getChatId() == null) {
            return super.createResponse(message);
        }

        String userText = message.getText();

        if (!geminiAiService.isEnabled()) {
            if (userText != null && userText.startsWith("/ai")) {
                String errorMsg = message.isUserMessage()
                        ? "Chức năng AI hiện chưa được kích hoạt. Vui lòng liên hệ quản trị viên để cấu hình GEMINI_API_KEY."
                        : "Chức năng AI chỉ sử dụng được trong chat riêng với bot.";
                return Optional.of(new SendMessage(String.valueOf(message.getChatId()), errorMsg));
            }
            return super.createResponse(message);
        }

        if (!message.isUserMessage()) {
            return userText != null && userText.startsWith("/ai")
                    ? Optional.of(new SendMessage(
                            String.valueOf(message.getChatId()),
                            "Chức năng AI chỉ sử dụng được trong chat riêng với bot."
                    ))
                    : super.createResponse(message);
        }

        com.example.dailyreportbot.entity.User user = null;
        if (message.getFrom() != null && message.getFrom().getId() != null) {
            user = userProfileService.findByTelegramUserId(message.getFrom().getId()).orElse(null);
        }
        String userContext = buildUserContext(user);

        if (userText != null && userText.startsWith("/ai ")) {
            String query = userText.substring(4).trim();
            if (!query.isBlank()) {
                try {
                    String aiResponse = geminiAiService.answerUserQuery(message.getChatId(), query, userContext, user);
                    if (aiResponse != null && !aiResponse.isBlank()) {
                        return Optional.of(new SendMessage(String.valueOf(message.getChatId()), aiResponse));
                    }
                } catch (RuntimeException exception) {
                    log.warn("Active AI response failed — chatId={}, errorType={}", 
                            message.getChatId(), exception.getClass().getSimpleName());
                }
                return Optional.of(new SendMessage(String.valueOf(message.getChatId()), "Xin lỗi, chức năng AI hiện không phản hồi. Vui lòng thử lại sau."));
            }
        }

        Optional<SendMessage> originalResponse = super.createResponse(message);
        if (originalResponse.isEmpty()) {
            return originalResponse;
        }

        SendMessage sendMessage = originalResponse.get();
        String responseText = sendMessage.getText();

        if (shouldIntercept(responseText) && userText != null && !userText.isBlank()) {
            try {
                String aiResponse = geminiAiService.getGuidanceResponse(message.getChatId(), userText, userContext);
                if (aiResponse != null && !aiResponse.isBlank()) {
                    sendMessage.setText(aiResponse);
                    log.info("AI response used — chatId={}, userTextLength={}", message.getChatId(), userText.length());
                }
            } catch (RuntimeException exception) {
                log.warn("AI response failed, using original — chatId={}, errorType={}", message.getChatId(), exception.getClass().getSimpleName());
            }
        }

        return originalResponse;
    }

    private boolean shouldIntercept(String responseText) {
        if (responseText == null) {
            return false;
        }
        return responseText.startsWith(UNSUPPORTED_COMMAND_PREFIX)
                || responseText.equals(REPORT_REQUIRED_TEXT);
    }

    private String buildUserContext(com.example.dailyreportbot.entity.User user) {
        StringBuilder context = new StringBuilder();
        context.append("Thời gian hiện tại: ").append(java.time.LocalDateTime.now(clock).toString()).append("\n");

        if (user != null) {
            context.append("Người dùng hiện tại:\n")
                   .append("- Tên: ").append(user.getFullName()).append("\n")
                   .append("- Mã NV: ").append(user.getEmployeeCode() != null ? user.getEmployeeCode() : "N/A").append("\n")
                   .append("- Phòng ban: ").append(user.getDepartmentName() != null ? user.getDepartmentName() : "N/A").append("\n")
                   .append("- Vai trò: ").append(user.isAdmin() ? "Admin" : (user.isManager() ? "Quản lý" : "Nhân viên")).append("\n");
        }
        return context.toString();
    }
}
