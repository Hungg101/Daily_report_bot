package com.example.dailyreportbot.web;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.DailyReportService;
import com.example.dailyreportbot.service.DailyReportSubmissionStatus;
import com.example.dailyreportbot.service.ReportSubmissionDetails;
import com.example.dailyreportbot.service.TelegramMiniAppInitDataValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

@RestController
@RequestMapping("/api/miniapp/reports")
/**
 * Web Controller cung cấp các API RESTful cho Telegram Mini App.
 * Đóng vai trò là cầu nối giao tiếp giữa giao diện Web App (Mini App)
 * và hệ thống backend để hiển thị, chỉnh sửa báo cáo hàng ngày.
 */
public class MiniAppReportController {

    private final TelegramBotProperties properties;
    private final TelegramMiniAppInitDataValidator initDataValidator;
    private final DailyReportService reportService;
    private final ObjectMapper objectMapper;

    public MiniAppReportController(
            TelegramBotProperties properties,
            TelegramMiniAppInitDataValidator initDataValidator,
            DailyReportService reportService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.initDataValidator = initDataValidator;
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    /**
     * Tiếp nhận dữ liệu báo cáo hàng ngày từ người dùng qua Mini App.
     * Xác thực thông tin khởi tạo Telegram, kiểm tra tính hợp lệ của báo cáo và lưu vào hệ thống.
     */
    @PostMapping
    public ResponseEntity<ApiResponse> submit(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            HttpServletRequest httpRequest
    ) {
        if (properties.validMiniAppUrl().isEmpty()) {
            return response(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "REPORT_NOT_SAVED",
                    "Mini App đang tạm tắt. Hãy dùng /report trong chat Telegram."
            );
        }

        OptionalLong telegramUserId = initDataValidator.validate(initData);
        if (telegramUserId.isEmpty()) {
            return response(
                    HttpStatus.UNAUTHORIZED,
                    "LAUNCH_INVALID",
                    "Phiên Mini App không hợp lệ hoặc đã hết hạn. Hãy mở lại từ /miniapp."
            );
        }

        ReportRequest request;
        try {
            JsonNode body = objectMapper.readTree(httpRequest.getInputStream());
            if (!isTextReport(body)) {
                return malformedRequest();
            }
            request = new ReportRequest(
                    body.get("title").textValue(),
                    body.get("collaborators").textValue(),
                    body.get("content").textValue()
            );
        } catch (JsonProcessingException exception) {
            return malformedRequest();
        } catch (java.io.IOException exception) {
            return malformedRequest();
        }

        ReportSubmissionDetails details;
        try {
            details = request == null
                    ? new ReportSubmissionDetails(null, null, null)
                    : new ReportSubmissionDetails(request.title(), request.collaborators(), request.content());
        } catch (ReportSubmissionDetails.ValidationException exception) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    "FIELD_INVALID",
                    fieldMessage(exception)
            );
        }

        DailyReportSubmissionStatus status;
        try {
            status = reportService.submitToday(telegramUserId.getAsLong(), details);
        } catch (RuntimeException exception) {
            return reportNotSaved();
        }

        return switch (status) {
            case SAVED -> response(HttpStatus.CREATED, "REPORT_SAVED", "Đã lưu báo cáo.");
            case BLANK_CONTENT -> response(HttpStatus.BAD_REQUEST, "FIELD_INVALID", "Nội dung không hợp lệ.");
            case TELEGRAM_USER_NOT_FOUND -> response(
                    HttpStatus.NOT_FOUND,
                    "USER_NOT_REGISTERED",
                    "Chưa đăng ký tài khoản. Hãy hoàn tất đăng ký trong chat riêng với bot."
            );
            case USER_INACTIVE -> response(
                    HttpStatus.FORBIDDEN,
                    "USER_INACTIVE",
                    "Tài khoản đang ngừng hoạt động nên không thể gửi báo cáo mới."
            );
            case IDENTITY_CHANGED -> response(
                    HttpStatus.CONFLICT,
                    "IDENTITY_CHANGED",
                    "Danh tính Telegram đã thay đổi. Hãy mở lại Mini App từ /miniapp."
            );
        };
    }

    private ResponseEntity<ApiResponse> malformedRequest() {
        return response(
                HttpStatus.BAD_REQUEST,
                "FIELD_INVALID",
                "Dữ liệu báo cáo không hợp lệ."
        );
    }

    private boolean isTextReport(JsonNode body) {
        return body != null
                && body.isObject()
                && body.path("title").isTextual()
                && body.path("collaborators").isTextual()
                && body.path("content").isTextual();
    }

    private ResponseEntity<ApiResponse> reportNotSaved() {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "REPORT_NOT_SAVED",
                "Chưa thể xác nhận đã lưu báo cáo. Hãy kiểm tra trước khi thử lại."
        );
    }

    private String fieldMessage(ReportSubmissionDetails.ValidationException exception) {
        String field = switch (exception.field()) {
            case "title" -> "Tiêu đề";
            case "collaborators" -> "Người cùng thực hiện";
            default -> "Nội dung";
        };
        return exception.tooLong()
                ? field + " vượt quá độ dài cho phép."
                : field + " không được để trống.";
    }

    private ResponseEntity<ApiResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiResponse(code, message));
    }

    public record ReportRequest(String title, String collaborators, String content) {

        @Override
        public String toString() {
            return "ReportRequest[redacted]";
        }
    }

    public record ApiResponse(String code, String message) {}
}
