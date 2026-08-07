package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.DailyReport;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.repository.DailyReportRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional(readOnly = true)
/**
 * Dịch vụ truy xuất dữ liệu báo cáo dành cho quản lý và quản trị viên.
 * Hỗ trợ Admin/Manager xem báo cáo tổng hợp của toàn bộ tổ chức 
 * hoặc của từng phòng ban/đội nhóm cụ thể.
 */
public class TeamReportReadService {

    private static final Comparator<DailyReport> REPORT_ORDER = Comparator
            .comparing(DailyReport::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(DailyReport::getId, Comparator.nullsLast(Comparator.reverseOrder()));

    private final UserRepository userRepository;
    private final DailyReportRepository reportRepository;
    private final TeamReportPolicy policy;

    public TeamReportReadService(
            UserRepository userRepository,
            DailyReportRepository reportRepository,
            TeamReportPolicy policy
    ) {
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.policy = policy;
    }

    /**
     * Đọc và đánh giá trạng thái nộp báo cáo của một cá nhân trong ngày cụ thể.
     * Xác định xem nhân viên đã nộp, nộp muộn, hay chưa nộp dựa trên quy định thời gian.
     */
    public TeamMemberReportStatus readUserStatus(
            long userId,
            LocalDate businessDate,
            Instant evaluatedAt,
            String timezoneId
    ) {
        if (userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        EvaluationTime evaluation = validateEvaluation(businessDate, evaluatedAt, timezoneId);
        User user = userRepository.findByInternalId(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        List<DailyReport> reports = reportRepository
                .findByUser_IdAndReportDateOrderByCreatedAtDescIdDesc(userId, businessDate);
        return toMemberStatus(user, businessDate, evaluation.localDateTime(), reports);
    }

    /**
     * Truy xuất báo cáo tổng hợp của một phòng ban hoặc đội nhóm.
     * Tính toán các chỉ số thống kê (tỷ lệ hoàn thành, danh sách nộp muộn)
     * dành cho cấp quản lý (Manager).
     */
    public TeamReportSummary readTeamSummary(
            TeamScope scope,
            LocalDate businessDate,
            Instant evaluatedAt,
            String timezoneId
    ) {
        if (scope == null) {
            throw new IllegalArgumentException("Team scope is required");
        }
        EvaluationTime evaluation = validateEvaluation(businessDate, evaluatedAt, timezoneId);
        return buildSummary(scope, businessDate, evaluatedAt, evaluation,
                userRepository.findCurrentTeamMembers(scope.departmentName(), scope.unitName()));
    }

    /**
     * Truy xuất báo cáo tổng hợp của toàn bộ tổ chức (tất cả nhân viên).
     * Cung cấp cái nhìn toàn cảnh về tình hình nộp báo cáo, dành riêng cho Admin.
     */
    public TeamReportSummary readOrganizationSummary(
            LocalDate businessDate,
            Instant evaluatedAt,
            String timezoneId
    ) {
        EvaluationTime evaluation = validateEvaluation(businessDate, evaluatedAt, timezoneId);
        return buildSummary(
                new TeamScope("Toàn hệ thống", null),
                businessDate,
                evaluatedAt,
                evaluation,
                userRepository.findAll()
        );
    }

    /**
     * Hàm nội bộ: Xây dựng bản tóm tắt báo cáo cho một nhóm hoặc toàn bộ hệ thống.
     * Chịu trách nhiệm tổng hợp dữ liệu từ danh sách người dùng và các báo cáo tương ứng,
     * tính toán thống kê và sắp xếp danh sách kết quả.
     */
    private TeamReportSummary buildSummary(
            TeamScope scope,
            LocalDate businessDate,
            Instant evaluatedAt,
            EvaluationTime evaluation,
            List<User> users
    ) {
        if (users.isEmpty()) {
            return new TeamReportSummary(
                    scope,
                    businessDate,
                    evaluatedAt,
                    evaluation.zoneId(),
                    0,
                    new EnumMap<>(TeamReportStatus.class),
                    List.of()
            );
        }

        List<Long> userIds = users.stream().map(User::getId).toList();
        List<DailyReport> reports = reportRepository
                .findByUser_IdInAndReportDateOrderByCreatedAtDescIdDesc(userIds, businessDate);
        Map<Long, List<DailyReport>> reportsByUser = new HashMap<>();
        for (DailyReport report : reports) {
            reportsByUser.computeIfAbsent(report.getUser().getId(), ignored -> new ArrayList<>()).add(report);
        }

        List<TeamMemberReportStatus> members = users.stream()
                .map(user -> toMemberStatus(
                        user,
                        businessDate,
                        evaluation.localDateTime(),
                        reportsByUser.getOrDefault(user.getId(), List.of())
                ))
                .sorted(Comparator
                        .comparing((TeamMemberReportStatus member) -> normalizeForOrdering(member.displayName()))
                        .thenComparing(TeamMemberReportStatus::userId))
                .toList();
        EnumMap<TeamReportStatus, Long> counts = new EnumMap<>(TeamReportStatus.class);
        for (TeamMemberReportStatus member : members) {
            counts.merge(member.status(), 1L, Long::sum);
        }
        return new TeamReportSummary(
                scope,
                businessDate,
                evaluatedAt,
                evaluation.zoneId(),
                members.size(),
                counts,
                members
        );
    }

    /**
     * Hàm nội bộ: Chuyển đổi dữ liệu báo cáo của một người dùng thành trạng thái (Status).
     * Áp dụng các quy tắc về thời gian nộp để kết luận nhân viên đó nộp đúng giờ,
     * nộp muộn, hay chưa nộp.
     */
    private TeamMemberReportStatus toMemberStatus(
            User user,
            LocalDate businessDate,
            LocalDateTime evaluatedAt,
            List<DailyReport> reports
    ) {
        List<DailyReport> qualifyingReports = reports.stream()
                .filter(report -> report.getCreatedAt() != null && !report.getCreatedAt().isAfter(evaluatedAt))
                .sorted(REPORT_ORDER)
                .toList();
        List<TeamReportEvidence> evidence = qualifyingReports.stream()
                .map(report -> new TeamReportEvidence(
                        report.getId(),
                        report.getReportDate(),
                        report.getContent(),
                        report.getCreatedAt()
                ))
                .toList();
        LocalDateTime firstSubmissionAt = qualifyingReports.stream()
                .map(DailyReport::getCreatedAt)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        TeamReportStatus status = policy.evaluate(
                user.getStatus(),
                businessDate,
                evaluatedAt,
                qualifyingReports.stream().map(DailyReport::getCreatedAt).toList()
        );

        return new TeamMemberReportStatus(
                user.getId(),
                user.getTelegramUserId(),
                trimToNull(user.getEmployeeCode()),
                trimToNull(user.getFullName()),
                trimToNull(user.getFirstName()),
                trimToNull(user.getUsername()),
                trimToNull(user.getDepartmentName()),
                trimToNull(user.getUnitName()),
                resolveDisplayName(user),
                status,
                firstSubmissionAt,
                evidence
        );
    }

    /**
     * Hàm nội bộ: Xác thực thông tin thời gian đánh giá (Evaluation Time).
     * Đảm bảo ngày kinh doanh và thời gian đánh giá hợp lệ theo múi giờ chỉ định.
     */
    private EvaluationTime validateEvaluation(LocalDate businessDate, Instant evaluatedAt, String timezoneId) {
        if (businessDate == null) {
            throw new IllegalArgumentException("Business date is required");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("Evaluation instant is required");
        }
        if (timezoneId == null || timezoneId.isBlank()) {
            throw new IllegalArgumentException("Timezone ID is required");
        }

        final ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezoneId.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid timezone ID: " + timezoneId, exception);
        }
        LocalDateTime localDateTime = LocalDateTime.ofInstant(evaluatedAt, zoneId);
        if (localDateTime.toLocalDate().isBefore(businessDate)) {
            throw new IllegalArgumentException("Evaluation instant is before business date");
        }
        return new EvaluationTime(zoneId, localDateTime);
    }

    /**
     * Hàm nội bộ: Xác định tên hiển thị tốt nhất cho người dùng.
     * Ưu tiên dùng Họ tên (FullName), sau đó là Tên (FirstName), Username, hoặc Mã nhân viên.
     */
    private String resolveDisplayName(User user) {
        for (String value : new String[]{
                user.getFullName(),
                user.getFirstName(),
                user.getUsername(),
                user.getEmployeeCode()
        }) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return "User " + user.getId();
    }

    /**
     * Hàm nội bộ: Chuẩn hóa chuỗi để phục vụ việc sắp xếp danh sách hiển thị.
     * Trả về chữ thường để so sánh không phân biệt hoa thường.
     */
    static String normalizeForOrdering(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record EvaluationTime(ZoneId zoneId, LocalDateTime localDateTime) {
    }
}
