package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.ReminderProperties;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Service
/**
 * Dịch vụ thực thi việc gửi nhắc nhở (Reminder).
 * Quản lý danh sách các nhân viên chưa nộp báo cáo và tiến hành
 * gửi tin nhắn nhắc nhở tự động qua Telegram theo lịch trình.
 */
public class ReminderRunService {

    private static final Logger log = LoggerFactory.getLogger(ReminderRunService.class);

    private final ReminderProperties properties;
    private final TeamReportReadService teamReportReadService;
    private final ReminderOccurrenceService occurrenceService;
    private final UserRepository userRepository;
    private final ReminderDeliveryPort deliveryPort;
    private final Clock reportClock;

    public ReminderRunService(
            ReminderProperties properties,
            TeamReportReadService teamReportReadService,
            ReminderOccurrenceService occurrenceService,
            UserRepository userRepository,
            ReminderDeliveryPort deliveryPort,
            Clock reportClock
    ) {
        this.properties = properties;
        this.teamReportReadService = teamReportReadService;
        this.occurrenceService = occurrenceService;
        this.userRepository = userRepository;
        this.deliveryPort = deliveryPort;
        this.reportClock = reportClock;
    }

    public void runInitialSlot() {
        RunContext context = approvedRunContext();
        if (context == null) {
            return;
        }

        for (Long userId : context.dueUserIds()) {
            claimSafely(userId, null, 1,
                    () -> occurrenceService.claimInitial(userId, context.businessDate()))
                    .ifPresent(this::deliverAndComplete);
        }
    }

    public void runFirstRetrySlot() {
        runRetrySlot(2, false);
    }

    public void runFinalRetrySlot() {
        runRetrySlot(3, true);
    }

    private void runRetrySlot(int attemptNumber, boolean finalSlot) {
        if (!properties.isApprovedAndEnabled()) {
            return;
        }

        LocalDate businessDate = currentBusinessDate();
        try {
            List<ReminderRetryCandidate> candidates = occurrenceService.findRetryEligible(businessDate);
            if (!candidates.isEmpty()) {
                RunContext context = readCurrentDueUsers(businessDate);
                for (ReminderRetryCandidate candidate : candidates) {
                    if (!context.dueUserIds().contains(candidate.userId())) {
                        suppressSafely(candidate, attemptNumber);
                        continue;
                    }
                    claimSafely(candidate.userId(), candidate.occurrenceId(), attemptNumber,
                            () -> occurrenceService.claimRetry(candidate.occurrenceId(), attemptNumber))
                            .ifPresent(this::deliverAndComplete);
                }
            }
        } finally {
            if (finalSlot) {
                expireSafely(businessDate);
            }
        }
    }

    private Optional<ReminderClaim> claimSafely(
            long userId,
            Long occurrenceId,
            int attemptNumber,
            Supplier<Optional<ReminderClaim>> claim
    ) {
        try {
            return claim.get();
        } catch (RuntimeException exception) {
            logCandidateFailure("claim", userId, occurrenceId, attemptNumber, exception);
            return Optional.empty();
        }
    }

    private void suppressSafely(ReminderRetryCandidate candidate, int attemptNumber) {
        try {
            occurrenceService.suppress(candidate.occurrenceId());
        } catch (RuntimeException exception) {
            logCandidateFailure(
                    "suppress",
                    candidate.userId(),
                    candidate.occurrenceId(),
                    attemptNumber,
                    exception
            );
        }
    }

    private void deliverAndComplete(ReminderClaim claim) {
        ReminderDeliveryOutcome outcome = resolveOutcome(claim);
        try {
            occurrenceService.complete(claim, outcome);
        } catch (RuntimeException exception) {
            logCandidateFailure(
                    "complete",
                    claim.userId(),
                    claim.occurrenceId(),
                    claim.attemptNumber(),
                    exception
            );
        }
    }

    private ReminderDeliveryOutcome resolveOutcome(ReminderClaim claim) {
        Long chatId;
        try {
            chatId = userRepository.findByInternalId(claim.userId()).map(User::getChatId).orElse(null);
        } catch (RuntimeException exception) {
            logCandidateFailure(
                    "lookup",
                    claim.userId(),
                    claim.occurrenceId(),
                    claim.attemptNumber(),
                    exception
            );
            return ReminderDeliveryOutcome.UNKNOWN_TRANSPORT;
        }
        if (chatId == null || chatId <= 0) {
            return ReminderDeliveryOutcome.MISSING_CHAT;
        }
        try {
            ReminderDeliveryOutcome outcome = deliveryPort.deliver(chatId);
            return outcome == null ? ReminderDeliveryOutcome.UNKNOWN_TRANSPORT : outcome;
        } catch (RuntimeException exception) {
            logCandidateFailure(
                    "deliver",
                    claim.userId(),
                    claim.occurrenceId(),
                    claim.attemptNumber(),
                    exception
            );
            return ReminderDeliveryOutcome.UNKNOWN_TRANSPORT;
        }
    }

    private void expireSafely(LocalDate businessDate) {
        try {
            occurrenceService.expireRetryEligible(businessDate);
        } catch (RuntimeException exception) {
            log.warn(
                    "Reminder processing failed - stage=expire, category={}, businessDate={}",
                    exception.getClass().getSimpleName(),
                    businessDate
            );
        }
    }

    private void logCandidateFailure(
            String stage,
            long userId,
            Long occurrenceId,
            int attemptNumber,
            RuntimeException exception
    ) {
        log.warn(
                "Reminder processing failed - stage={}, category={}, userId={}, occurrenceId={}, attempt={}",
                stage,
                exception.getClass().getSimpleName(),
                userId,
                occurrenceId,
                attemptNumber
        );
    }

    private RunContext approvedRunContext() {
        return properties.isApprovedAndEnabled() ? readCurrentDueUsers(currentBusinessDate()) : null;
    }

    private RunContext readCurrentDueUsers(LocalDate businessDate) {
        TeamReportSummary summary = teamReportReadService.readTeamSummary(
                properties.approvedScope(),
                businessDate,
                reportClock.instant(),
                ReminderProperties.APPROVED_TIME_ZONE
        );
        Set<Long> dueUserIds = summary.members().stream()
                .filter(member -> member.status() == TeamReportStatus.DUE)
                .map(TeamMemberReportStatus::userId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new RunContext(businessDate, dueUserIds);
    }

    private LocalDate currentBusinessDate() {
        return reportClock.instant()
                .atZone(ZoneId.of(ReminderProperties.APPROVED_TIME_ZONE))
                .toLocalDate();
    }

    private record RunContext(LocalDate businessDate, Set<Long> dueUserIds) {}
}
