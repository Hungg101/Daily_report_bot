package com.example.dailyreportbot.service;

import com.example.dailyreportbot.dto.GeminiRequest;
import com.example.dailyreportbot.dto.GeminiResponse;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls the Google Gemini generative AI API and manages per-chat conversation history.
 */
@Service
/**
 * Dịch vụ tích hợp AI Gemini.
 * Cung cấp khả năng tổng hợp báo cáo và thay đổi thông tin nhân sự bằng ngôn ngữ tự nhiên.
 * Đóng vai trò là trợ lý ảo hỗ trợ Admin/Manager và người dùng tương tác qua tin nhắn Telegram.
 */
public class GeminiAiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);
    private static final int MAX_HISTORY_SIZE = 10;

    private static final String SYSTEM_INSTRUCTION = """
            Bạn là trợ lý AI của Bot Báo cáo Công việc Hằng ngày trên Telegram.
            Nhiệm vụ của bạn:
            - Trả lời thân thiện, lịch sự bằng tiếng Việt.
            - Giúp người dùng hiểu cách sử dụng bot và giải đáp thắc mắc.
            - Các lệnh hợp lệ: /start, /help, /report, /status, /cancel, /myreports, /reports YYYY-MM-DD, /employeeinfo, /teamstatus, /manage, /miniapp và /ai <câu hỏi>.
            - Hướng dẫn người dùng gửi lệnh /report để nộp báo cáo công việc.
            - Khi người dùng muốn xem báo cáo, HÃY TỰ ĐỘNG GỌI HÀM lấy báo cáo thay vì từ chối.
            - Chỉ gọi hàm quản trị khi người dùng yêu cầu rõ ràng việc chuyển phòng ban hoặc đổi trạng thái; không tự suy đoán mã nhân viên, họ tên, phòng ban hay trạng thái.
            - Ưu tiên mã nhân viên. Nếu họ tên không đầy đủ hoặc có thể trùng, hãy hỏi lại mã nhân viên trước khi gọi hàm.
            - Chỉ thông báo thành công khi kết quả hàm xác nhận thao tác đã thành công.
            - Trả lời ngắn gọn, rõ ràng, không dài quá 500 ký tự trừ khi người dùng yêu cầu chi tiết.
            """;

    private final String apiKey;
    private final String model;
    private final String reportTimeZone;
    private final Clock clock;
    private final RestClient restClient;
    private final TeamReportReadService teamReportReadService;
    private final UserRepository userRepository;
    private final IdentityAdministrationService identityAdministrationService;
    private final Map<Long, LinkedList<ChatMessage>> chatHistories = new ConcurrentHashMap<>();

    @Autowired
    public GeminiAiService(
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.api.model:gemini-3.6-flash}") String model,
            @Value("${app.report-time-zone:Asia/Ho_Chi_Minh}") String reportTimeZone,
            Clock clock,
            TeamReportReadService teamReportReadService,
            UserRepository userRepository,
            IdentityAdministrationService identityAdministrationService
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gemini-3.6-flash" : model.trim();
        this.reportTimeZone = reportTimeZone;
        this.clock = clock;
        this.restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
        this.teamReportReadService = teamReportReadService;
        this.userRepository = userRepository;
        this.identityAdministrationService = identityAdministrationService;

        if (this.apiKey.isEmpty()) {
            log.info("Gemini AI is disabled — GEMINI_API_KEY is not configured.");
        }
    }

    // Constructor dành cho Unit Test
    public GeminiAiService(String apiKey, String model, String reportTimeZone, Clock clock, RestClient restClient, TeamReportReadService teamReportReadService, UserRepository userRepository, IdentityAdministrationService identityAdministrationService) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null || model.isBlank() ? "gemini-3.6-flash" : model;
        this.reportTimeZone = reportTimeZone;
        this.clock = clock;
        this.restClient = restClient;
        this.teamReportReadService = teamReportReadService;
        this.userRepository = userRepository;
        this.identityAdministrationService = identityAdministrationService;
    }

    /**
     * Lấy phản hồi từ AI cho các câu hỏi hướng dẫn sử dụng bot.
     * Dùng khi người dùng hỏi cách dùng chức năng hoặc thắc mắc chung.
     */
    public String getGuidanceResponse(Long chatId, String userMessage, String userContext) {
        return processRequest(chatId, userMessage, userContext, null, false);
    }

    /**
     * Trả lời truy vấn của người dùng, có thể kích hoạt các hàm (functions) của hệ thống.
     * Hỗ trợ chức năng tổng hợp báo cáo và quản lý nhân sự bằng cách dịch câu hỏi tự nhiên
     * thành các lệnh gọi API nội bộ tương ứng.
     */
    public String answerUserQuery(Long chatId, String userQuery, String userContext, User user) {
        return processRequest(chatId, userQuery, userContext, user, true);
    }

    /**
     * Hàm nội bộ: Xử lý quy trình gửi yêu cầu lên API Gemini và nhận phản hồi.
     * Chịu trách nhiệm duy trì lịch sử trò chuyện (chat history), gọi API, và
     * xử lý các "Function Calling" (gọi hàm) nếu AI yêu cầu.
     */
    private String processRequest(Long chatId, String userInput, String userContext, User user, boolean includeTools) {
        if (apiKey.isEmpty() || chatId == null || userInput == null || userInput.isBlank()) {
            return null;
        }

        try {
            LinkedList<ChatMessage> history = chatHistories.computeIfAbsent(chatId, k -> new LinkedList<>());
            List<GeminiRequest.Content> contents = buildContentsDto(history, userInput);
            GeminiRequest request = buildRequestDto(contents, userContext, includeTools);

            GeminiResponse response = callApi(request);
            if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
                return null;
            }

            GeminiRequest.Content content = response.candidates().get(0).content();
            if (content == null || content.parts() == null || content.parts().isEmpty()) {
                return null;
            }

            boolean hasFunctionCalls = false;
            List<GeminiRequest.Part> functionResponses = new ArrayList<>();
            
            for (GeminiRequest.Part part : content.parts()) {
                if (part.functionCall() != null && includeTools) {
                    hasFunctionCalls = true;
                    String funcName = part.functionCall().name();
                    Map<String, Object> args = part.functionCall().args();
                    String callId = part.functionCall().id();
                    
                    Object funcResult = executeFunction(funcName, args, user, userInput);
                    
                    GeminiRequest.FunctionResponse fr = new GeminiRequest.FunctionResponse(funcName, Map.of("result", funcResult), callId);
                    functionResponses.add(new GeminiRequest.Part(null, null, fr, null));
                }
            }
            
            if (hasFunctionCalls) {
                contents.add(content);
                contents.add(new GeminiRequest.Content("user", functionResponses));
                
                GeminiRequest followUpRequest = buildRequestDto(contents, userContext, false);
                GeminiResponse followUpResponse = callApi(followUpRequest);
                
                String finalAiText = extractText(followUpResponse);
                if (finalAiText != null && !finalAiText.isBlank()) {
                    addToHistory(history, userInput, finalAiText);
                    return finalAiText;
                }
            } else if (content.parts().get(0).text() != null) {
                String aiText = content.parts().get(0).text();
                addToHistory(history, userInput, aiText);
                return aiText;
            }
            return null;
        } catch (RuntimeException exception) {
            log.warn("Gemini AI call failed — chatId={}, errorType={}, message={}", chatId, exception.getClass().getSimpleName(), exception.getMessage());
            return null;
        }
    }

    /**
     * Hàm nội bộ (có thể test): Thực thi các hàm nghiệp vụ được AI chỉ định.
     * Phân tích tên hàm (ví dụ: get_report_summary, update_user_department)
     * và gọi đến các Service tương ứng để lấy dữ liệu thực tế.
     */
    Object executeFunction(String funcName, Map<String, Object> args, User user, String userInput) {
        User actor = findCurrentActor(user);
        if ("get_report_summary".equals(funcName)) {
            try {
                String dateStr = argument(args, "date");
                String dept = argument(args, "department");
                 
                LocalDate today = LocalDate.now(clock);
                LocalDate queryDate = dateStr != null ? LocalDate.parse(dateStr) : today;
                 

                if (actor == null) {
                    return "Không xác định được người dùng đang hoạt động.";
                }

                if (actor.isAdmin()) {
                    if (dept != null && !dept.isBlank()) {
                        return teamReportReadService.readTeamSummary(new TeamScope(dept, null), queryDate, Instant.now(clock), reportTimeZone);
                    }
                    return teamReportReadService.readOrganizationSummary(queryDate, Instant.now(clock), reportTimeZone);
                } else if (actor.isManager()) {
                    String userDept = actor.getDepartmentName() != null ? actor.getDepartmentName().trim() : "";
                    if (userDept.isEmpty()) {
                        return "Bạn chưa được phân bổ vào phòng ban nên không thể xem báo cáo đội nhóm.";
                    }
                    if (dept != null && !dept.isBlank() && !dept.equalsIgnoreCase(userDept)) {
                        return "Bạn không có quyền xem báo cáo của phòng này.";
                    }
                    return teamReportReadService.readTeamSummary(new TeamScope(userDept, null), queryDate, Instant.now(clock), reportTimeZone);
                } else {
                    if (dept != null && !dept.isBlank()) {
                        return "Bạn là nhân viên, không có quyền xem báo cáo của phòng ban hoặc tổ chức.";
                    }
                    return teamReportReadService.readUserStatus(actor.getId(), queryDate, Instant.now(clock), reportTimeZone);
                }
            } catch (Exception e) {
                log.warn("Function execution error", e);
                return "Đã xảy ra lỗi khi truy xuất dữ liệu từ hệ thống.";
            }
        } else if ("update_user_department".equals(funcName)) {
            String identifier = argument(args, "employee_identifier");
            String newDept = argument(args, "new_department");
            if (actor == null || !actor.isAdmin()) {
                return "Bạn không có quyền chuyển phòng ban cho nhân viên.";
            }
            if (newDept == null) {
                return "Vui lòng cung cấp chính xác phòng ban đích.";
            }
            if (!mentions(userInput, identifier) || !mentions(userInput, newDept)) {
                return "Để tránh cập nhật nhầm, vui lòng nêu rõ mã nhân viên hoặc họ tên đầy đủ và phòng ban đích trong cùng yêu cầu.";
            }
            List<User> matches = findTargetUsers(identifier);
            String lookupError = targetLookupError(identifier, matches);
            if (lookupError != null) {
                return lookupError;
            }
            User targetUser = matches.get(0);
            IdentityAdminResult result = identityAdministrationService.changeDepartment(
                    actor.getTelegramUserId(),
                    targetUser.getTelegramUserId(),
                    newDept,
                    targetUser.getUnitName(),
                    "Cập nhật qua AI Chat"
            );
            if (result.status() == IdentityAdminStatus.UPDATED) {
                return "Thành công: Đã chuyển nhân viên " + targetUser.getFullName() + " sang phòng " + newDept;
            } else if (result.status() == IdentityAdminStatus.NO_CHANGE) {
                return "Không có thay đổi nào. Nhân viên đã ở phòng " + newDept;
            } else if (result.status() == IdentityAdminStatus.ACCESS_DENIED) {
                return "Bạn không có quyền chuyển phòng ban cho nhân viên.";
            } else {
                return "Thất bại: Không thể cập nhật phòng ban. Lỗi: " + result.status();
            }
        } else if ("change_user_status".equals(funcName)) {
            if (actor == null || (!actor.isAdmin() && !actor.isManager())) {
                return "Bạn không có quyền quản trị.";
            }
            String identifier = argument(args, "employee_identifier");
            String status = argument(args, "status");
            UserStatus desiredStatus;
            try {
                desiredStatus = UserStatus.valueOf(status != null ? status.toUpperCase(Locale.ROOT) : "");
            } catch (Exception e) {
                return "Trạng thái không hợp lệ. Vui lòng chọn ACTIVE hoặc INACTIVE.";
            }
            if (!mentions(userInput, identifier) || !mentions(userInput, status)) {
                return "Để tránh cập nhật nhầm, vui lòng nêu rõ mã nhân viên hoặc họ tên đầy đủ và trạng thái ACTIVE/INACTIVE trong cùng yêu cầu.";
            }
            List<User> matches = findTargetUsers(identifier);
            String lookupError = targetLookupError(identifier, matches);
            if (lookupError != null) {
                return lookupError;
            }
            User targetUser = matches.get(0);

            IdentityAdminResult result = identityAdministrationService.changeStatus(
                    actor.getTelegramUserId(),
                    targetUser.getTelegramUserId(),
                    desiredStatus,
                    "Cập nhật qua AI Chat"
            );
            if (result.status() == IdentityAdminStatus.REACTIVATED || result.status() == IdentityAdminStatus.DEACTIVATED) {
                return "Thành công: Đã chuyển trạng thái nhân viên " + targetUser.getFullName() + " thành " + desiredStatus;
            } else if (result.status() == IdentityAdminStatus.NO_CHANGE) {
                return "Không có thay đổi nào. Trạng thái hiện tại đã là " + desiredStatus;
            } else if (result.status() == IdentityAdminStatus.ACCESS_DENIED) {
                return "Bạn không có quyền đổi trạng thái của nhân viên này.";
            } else {
                return "Thất bại: Không thể cập nhật trạng thái. Lỗi: " + result.status();
            }
        }
        return "Unknown function";
    }

    /**
     * Hàm nội bộ: Gửi HTTP POST request trực tiếp đến endpoint của Google Gemini.
     */
    private GeminiResponse callApi(GeminiRequest request) {
        return restClient.post()
                .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                .header("Content-Type", "application/json")
                .body(request)
                .retrieve()
                .body(GeminiResponse.class);
    }

    /**
     * Hàm nội bộ: Chuyển đổi lịch sử chat cục bộ thành định dạng cấu trúc Content
     * mà API Gemini yêu cầu (phân loại user và model).
     */
    private List<GeminiRequest.Content> buildContentsDto(LinkedList<ChatMessage> history, String userMessage) {
        List<GeminiRequest.Content> contents = new ArrayList<>();
        synchronized (history) {
            for (ChatMessage entry : history) {
                contents.add(new GeminiRequest.Content(entry.role(), List.of(new GeminiRequest.Part(entry.text(), null, null, null))));
            }
        }
        contents.add(new GeminiRequest.Content("user", List.of(new GeminiRequest.Part(userMessage, null, null, null))));
        return contents;
    }

    /**
     * Hàm nội bộ: Đóng gói toàn bộ request gửi cho AI, bao gồm chỉ thị hệ thống
     * (System Instruction) và khai báo cấu trúc các hàm khả dụng (Tools/Functions).
     */
    private GeminiRequest buildRequestDto(List<GeminiRequest.Content> contents, String userContext, boolean includeTools) {
        String fullInstruction = SYSTEM_INSTRUCTION;
        if (userContext != null && !userContext.isBlank()) {
            fullInstruction = userContext + "\n\n---\n\n" + SYSTEM_INSTRUCTION;
        }

        GeminiRequest.Content sysInst = new GeminiRequest.Content("system", List.of(new GeminiRequest.Part(fullInstruction, null, null, null)));
        
        List<GeminiRequest.Tool> tools = null;
        if (includeTools) {
            GeminiRequest.FunctionDeclaration decl = new GeminiRequest.FunctionDeclaration(
                    "get_report_summary",
                    "Lấy tóm tắt báo cáo công việc của nhân viên, phòng ban hoặc toàn công ty theo ngày.",
                    new GeminiRequest.Parameters(
                            "OBJECT",
                            Map.of(
                                    "date", new GeminiRequest.Schema("STRING", "Ngày cần xem báo cáo (định dạng YYYY-MM-DD)."),
                                    "department", new GeminiRequest.Schema("STRING", "Tên phòng ban cần xem (bỏ qua nếu xem của mình hoặc toàn công ty).")
                            ),
                            List.of("date")
                    )
            );
            GeminiRequest.FunctionDeclaration updateDeptDecl = new GeminiRequest.FunctionDeclaration(
                    "update_user_department",
                    "Chuyển phòng ban khi Admin yêu cầu rõ ràng. Không gọi cho câu hỏi giả định hoặc khi thiếu định danh hay phòng đích.",
                    new GeminiRequest.Parameters(
                            "OBJECT",
                            Map.of(
                                    "employee_identifier", new GeminiRequest.Schema("STRING", "Mã nhân viên (ưu tiên) hoặc họ tên đầy đủ chính xác."),
                                    "new_department", new GeminiRequest.Schema("STRING", "Tên phòng ban đích do người dùng nêu rõ, không tự suy đoán.")
                            ),
                            List.of("employee_identifier", "new_department")
                    )
            );
            GeminiRequest.FunctionDeclaration changeStatusDecl = new GeminiRequest.FunctionDeclaration(
                    "change_user_status",
                    "Đổi trạng thái khi Quản lý hoặc Admin yêu cầu rõ ràng. Không gọi cho câu hỏi giả định hoặc khi thiếu định danh hay trạng thái.",
                    new GeminiRequest.Parameters(
                            "OBJECT",
                            Map.of(
                                    "employee_identifier", new GeminiRequest.Schema("STRING", "Mã nhân viên (ưu tiên) hoặc họ tên đầy đủ chính xác."),
                                    "status", new GeminiRequest.Schema("STRING", "Trạng thái mới (ACTIVE hoặc INACTIVE).")
                            ),
                            List.of("employee_identifier", "status")
                    )
            );
            tools = List.of(new GeminiRequest.Tool(List.of(decl, updateDeptDecl, changeStatusDecl)));
        }

        return new GeminiRequest(contents, sysInst, tools);
    }

    /**
     * Hàm nội bộ: Lấy thông tin tài khoản người dùng hiện tại đang chat với bot,
     * đảm bảo tài khoản phải đang ở trạng thái ACTIVE (đang hoạt động).
     */
    private User findCurrentActor(User user) {
        if (user == null || user.getTelegramUserId() == null) {
            return null;
        }
        return userRepository.findByTelegramUserId(user.getTelegramUserId())
                .filter(current -> current.getStatus() == UserStatus.ACTIVE)
                .orElse(null);
    }

    /**
     * Hàm nội bộ: Tìm kiếm nhân viên mục tiêu dựa vào mã nhân viên hoặc họ tên.
     * Ưu tiên tìm theo mã nhân viên (Employee Code) trước.
     */
    private List<User> findTargetUsers(String identifier) {
        if (identifier == null) {
            return List.of();
        }
        String exactIdentifier = identifier.trim();
        Optional<User> employeeCodeMatch = userRepository.findByEmployeeCode(
                exactIdentifier.toUpperCase(Locale.ROOT)
        );
        return employeeCodeMatch.map(List::of)
                .orElseGet(() -> userRepository.findAllByFullNameIgnoreCase(exactIdentifier));
    }

    private String targetLookupError(String identifier, List<User> matches) {
        if (identifier == null) {
            return "Vui lòng cung cấp mã nhân viên hoặc họ tên đầy đủ chính xác.";
        }
        if (matches.isEmpty()) {
            return "Không tìm thấy nhân viên khớp chính xác với mã hoặc họ tên: " + identifier;
        }
        if (matches.size() > 1) {
            return "Có nhiều nhân viên trùng tên. Vui lòng dùng mã nhân viên để tránh cập nhật nhầm.";
        }
        if (matches.get(0).getTelegramUserId() == null) {
            return "Nhân viên chưa liên kết tài khoản Telegram nên chưa thể cập nhật qua AI.";
        }
        return null;
    }

    private String argument(Map<String, Object> args, String name) {
        if (args == null || args.get(name) == null) {
            return null;
        }
        String value = String.valueOf(args.get(name)).trim();
        return value.isEmpty() ? null : value;
    }

    private boolean mentions(String userInput, String value) {
        return userInput != null
                && value != null
                && userInput.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        GeminiRequest.Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return null;
        }
        return content.parts().get(0).text();
    }

    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    private void addToHistory(LinkedList<ChatMessage> history, String userMessage, String aiResponse) {
        synchronized (history) {
            history.addLast(new ChatMessage("user", userMessage));
            history.addLast(new ChatMessage("model", aiResponse));
            while (history.size() > MAX_HISTORY_SIZE * 2) {
                history.removeFirst();
                if (!history.isEmpty()) {
                    history.removeFirst();
                }
            }
        }
    }

    record ChatMessage(String role, String text) {}
}
