package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

@Service
/**
 * Dịch vụ xử lý nghiệp vụ chính của báo cáo hàng ngày (Daily Report).
 * Cung cấp các chức năng thêm mới, cập nhật, xóa, nộp và xem báo cáo
 * theo cá nhân trong hệ thống.
 */
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    private final DailyReportRepository dailyReportRepository;
    private final UserRepository userRepository;
    private final Clock reportClock;

    public DailyReportService(
            DailyReportRepository dailyReportRepository,
            UserRepository userRepository,
            Clock reportClock
    ) {
        this.dailyReportRepository = dailyReportRepository;
        this.userRepository = userRepository;
        this.reportClock = reportClock;
    }

    @Transactional
    /**
     * Xử lý lưu báo cáo trong ngày thông qua tin nhắn văn bản thuần túy.
     * Xác thực trạng thái người dùng (đã đăng ký, đã phê duyệt) trước khi
     * lưu trữ nội dung vào cơ sở dữ liệu.
     */
    public DailyReportSubmissionStatus submitToday(Long telegramUserId, String content) {
        return submitToday(telegramUserId, content, null, Instant.now(reportClock));
    }

    /**
     * Tương tự submitToday nhưng cho phép chỉ định thời gian nộp cụ thể (submissionInstant).
     * Dùng chủ yếu cho mục đích giả lập dữ liệu hoặc test.
     */
    @Transactional
    public DailyReportSubmissionStatus submitToday(
            Long telegramUserId,
            String content,
            Instant submissionInstant
    ) {
        return submitToday(telegramUserId, content, null, submissionInstant);
    }

    /**
     * Nộp báo cáo với tùy chọn đính kèm tên những người cùng hợp tác (collaborators).
     * Rất hữu ích khi một nhóm cùng làm chung một task và chỉ một người đại diện báo cáo.
     */
    @Transactional
    public DailyReportSubmissionStatus submitToday(
            Long telegramUserId,
            String content,
            String collaborators,
            Instant submissionInstant
    ) {
        if (!StringUtils.hasText(content)) {
            return DailyReportSubmissionStatus.BLANK_CONTENT;
        }

        return resolveCurrentOwnerId(telegramUserId)
                .map(ownerId -> submitForLockedOwner(
                        telegramUserId,
                        ownerId,
                        ignored -> content.trim(),
                        collaborators,
                        submissionInstant
                ))
                .orElse(DailyReportSubmissionStatus.TELEGRAM_USER_NOT_FOUND);
    }

    /**
     * Nộp báo cáo nhưng kiểm tra chặt chẽ xem User ID (expectedOwnerId) có bị thay đổi không.
     * Phòng trường hợp người dùng bị thay đổi tài khoản trong lúc đang soạn tin.
     */
    @Transactional
    public DailyReportSubmissionStatus submitToday(
            Long telegramUserId,
            Long expectedOwnerId,
            String content,
            String collaborators,
            Instant submissionInstant
    ) {
        if (!StringUtils.hasText(content)) {
            return DailyReportSubmissionStatus.BLANK_CONTENT;
        }
        if (expectedOwnerId == null) {
            return DailyReportSubmissionStatus.IDENTITY_CHANGED;
        }

        return submitForLockedOwner(
                telegramUserId,
                expectedOwnerId,
                ignored -> content.trim(),
                collaborators,
                submissionInstant
        );
    }

    @Transactional
    /**
     * Xử lý nộp báo cáo trong ngày từ giao diện cấu trúc (Web Mini App).
     * Tiếp nhận dữ liệu có phân chia rõ ràng các trường (Nhiệm vụ hoàn thành,
     * Kế hoạch ngày mai, Vấn đề gặp phải) để lưu vào hệ thống.
     */
    public DailyReportSubmissionStatus submitToday(Long telegramUserId, ReportSubmissionDetails details) {
        return submitToday(telegramUserId, details, Instant.now(reportClock));
    }

    /**
     * Nộp báo cáo chi tiết từ Mini App với thời gian được chỉ định rõ ràng.
     * Xử lý serialize JSON các trường thông tin ra dạng chuỗi nội dung văn bản.
     */
    @Transactional
    public DailyReportSubmissionStatus submitToday(
            Long telegramUserId,
            ReportSubmissionDetails details,
            Instant submissionInstant
    ) {
        if (details == null) {
            return DailyReportSubmissionStatus.BLANK_CONTENT;
        }

        return resolveCurrentOwnerId(telegramUserId)
                .map(ownerId -> submitForLockedOwner(
                        telegramUserId,
                        ownerId,
                        user -> details.serialize(ReportSubmissionDetails.primaryPerformer(user, telegramUserId)),
                        details.collaborators(),
                        submissionInstant
                ))
                .orElse(DailyReportSubmissionStatus.TELEGRAM_USER_NOT_FOUND);
    }

    @Transactional(readOnly = true)
    /**
     * Lấy danh sách các báo cáo công việc gần đây nhất của một nhân viên.
     * Sử dụng cho tính năng xem lịch sử báo cáo cá nhân (lệnh /myreports).
     */
    public List<DailyReport> findRecentForTelegramUser(Long telegramUserId, int limit) {
        if (telegramUserId == null || limit <= 0) {
            return List.of();
        }

        return dailyReportRepository.findByUser_TelegramUserIdOrderByCreatedAtDescIdDesc(
                telegramUserId,
                PageRequest.of(0, limit)
        );
    }

    /**
     * Tìm kiếm báo cáo của một nhân viên trong một ngày cụ thể.
     * Sử dụng cho chức năng xem lại lịch sử báo cáo theo ngày (lệnh /reports YYYY-MM-DD).
     */
    @Transactional(readOnly = true)
    public List<DailyReport> findForTelegramUserOnDate(Long telegramUserId, LocalDate reportDate) {
        if (telegramUserId == null || reportDate == null) {
            return List.of();
        }

        return dailyReportRepository.findByUser_TelegramUserIdAndReportDateOrderByCreatedAtDescIdDesc(
                telegramUserId,
                reportDate
        );
    }

    /**
     * Hàm nội bộ: Thực hiện khởi tạo entity DailyReport và lưu vào Database.
     * Xử lý gán các thông tin như thời gian nộp, phòng ban, người thực hiện.
     */
    private DailyReportSubmissionStatus saveReport(
            User user,
            String content,
            String collaborators,
            Instant submissionInstant
    ) {
        LocalDateTime submittedAt = LocalDateTime.ofInstant(submissionInstant, reportClock.getZone());
        DailyReport dailyReport = new DailyReport();
        dailyReport.setUser(user);
        dailyReport.setReportDate(submittedAt.toLocalDate());
        dailyReport.setCreatedAt(submittedAt);
        dailyReport.setContent(content);
        dailyReport.setPerformer(ReportSubmissionDetails.primaryPerformer(user, user.getTelegramUserId()));
        dailyReport.setCollaborators(collaborators);
        dailyReport.setDepartment(user.getDepartmentName());
        dailyReport.setUnit(user.getUnitName());

        DailyReport savedReport = dailyReportRepository.save(dailyReport);
        Long reportId = savedReport != null ? savedReport.getId() : dailyReport.getId();
        log.info("Daily report saved - reportId={}", reportId);
        return DailyReportSubmissionStatus.SAVED;
    }

    /**
     * Hàm nội bộ: Trích xuất ID nội bộ của hệ thống (Internal ID) từ Telegram User ID.
     */
    private Optional<Long> resolveCurrentOwnerId(Long telegramUserId) {
        return telegramUserId == null
                ? Optional.empty()
                : userRepository.findInternalIdByTelegramUserId(telegramUserId);
    }

    /**
     * Hàm nội bộ: Khóa (Lock) bản ghi User trong Database để tránh tình trạng Race Condition
     * khi người dùng bấm nộp báo cáo liên tục nhiều lần cùng một lúc.
     * Trả về lỗi nếu tài khoản bị vô hiệu hóa hoặc sai ID.
     */
    private DailyReportSubmissionStatus submitForLockedOwner(
            Long telegramUserId,
            long ownerId,
            Function<User, String> content,
            String collaborators,
            Instant submissionInstant
    ) {
        User user = userRepository.findLockedById(ownerId).orElse(null);
        if (user == null || !Objects.equals(user.getTelegramUserId(), telegramUserId)) {
            return DailyReportSubmissionStatus.IDENTITY_CHANGED;
        }
        if (user.getStatus() == UserStatus.INACTIVE) {
            return DailyReportSubmissionStatus.USER_INACTIVE;
        }
        return saveReport(user, content.apply(user), collaborators, submissionInstant);
    }
}
