package com.example.dailyreportbot.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dailyreportbot.config.ReminderProperties;
import com.example.dailyreportbot.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

@ExtendWith(MockitoExtension.class)
class ReminderRunServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);
    private static final Instant EVALUATED_AT = Instant.parse("2026-07-14T09:30:00Z");
    private static final String PRIVATE_FAILURE_DETAIL =
            "chatId=99001 report=private token=secret raw-response=private";

    @Mock
    private TeamReportReadService teamReportReadService;

    @Mock
    private ReminderOccurrenceService occurrenceService;

    @Mock
    private com.example.dailyreportbot.repository.UserRepository userRepository;

    @Mock
    private ReminderDeliveryPort deliveryPort;

    private ReminderProperties properties;
    private ReminderRunService service;

    @BeforeEach
    void setUp() {
        properties = approvedProperties();
        service = new ReminderRunService(
                properties,
                teamReportReadService,
                occurrenceService,
                userRepository,
                deliveryPort,
                Clock.fixed(EVALUATED_AT, ZoneId.of("Asia/Ho_Chi_Minh"))
        );
    }

    @Test
    void shouldDoNothingWhenReminderPolicyIsDisabledOrInvalid() {
        properties.setEnabled(false);

        service.runInitialSlot();

        verifyNoInteractions(teamReportReadService, occurrenceService, userRepository, deliveryPort);

        properties.setEnabled(true);
        properties.setDepartment("   ");
        service.runFirstRetrySlot();

        verifyNoInteractions(teamReportReadService, occurrenceService, userRepository, deliveryPort);

        properties.setDepartment("Engineering");
        properties.setTimeZone("UTC");
        service.runFinalRetrySlot();

        verifyNoInteractions(teamReportReadService, occurrenceService, userRepository, deliveryPort);
    }

    @Test
    void shouldClaimAndDeliverOnlyCurrentM4DueMembersInConfiguredDepartmentAndUnit() {
        TeamReportSummary summary = summary(
                member(101L, TeamReportStatus.DUE, "private report content"),
                member(102L, TeamReportStatus.SUBMITTED_ON_TIME, "submitted content"),
                member(103L, TeamReportStatus.SUBMITTED_LATE, "late content"),
                member(104L, TeamReportStatus.ABSENT, "absent content"),
                member(105L, TeamReportStatus.EXCLUDED, "excluded content")
        );
        ReminderClaim claim = new ReminderClaim(501L, 101L, 1);
        User user = userWithChat(101L, 90001L);
        when(teamReportReadService.readTeamSummary(
                new TeamScope("Engineering", "Platform"), BUSINESS_DATE, EVALUATED_AT, "Asia/Ho_Chi_Minh"
        )).thenReturn(summary);
        when(occurrenceService.claimInitial(101L, BUSINESS_DATE)).thenReturn(Optional.of(claim));
        when(userRepository.findByInternalId(101L)).thenReturn(Optional.of(user));
        when(deliveryPort.deliver(90001L)).thenReturn(ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);

        service.runInitialSlot();

        verify(occurrenceService).claimInitial(101L, BUSINESS_DATE);
        verify(occurrenceService, never()).claimInitial(eq(102L), any());
        verify(occurrenceService, never()).claimInitial(eq(103L), any());
        verify(occurrenceService, never()).claimInitial(eq(104L), any());
        verify(occurrenceService, never()).claimInitial(eq(105L), any());
        verify(deliveryPort).deliver(90001L);
        verify(occurrenceService).complete(claim, ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);
        verify(userRepository, never()).findByInternalId(102L);
    }

    @Test
    void shouldUseTheConfiguredDepartmentWithoutUnitAndNotReadReportEvidenceDirectly() {
        properties.setUnit(" ");
        TeamReportSummary summary = summary(member(101L, TeamReportStatus.DUE, "sensitive report evidence"));
        when(teamReportReadService.readTeamSummary(
                new TeamScope("Engineering", null), BUSINESS_DATE, EVALUATED_AT, "Asia/Ho_Chi_Minh"
        )).thenReturn(summary);
        when(occurrenceService.claimInitial(101L, BUSINESS_DATE)).thenReturn(Optional.empty());

        service.runInitialSlot();

        verify(occurrenceService).claimInitial(101L, BUSINESS_DATE);
        verifyNoInteractions(userRepository, deliveryPort);
    }

    @Test
    void shouldNotDeliverTwiceWhenTheInitialOccurrenceWasAlreadyClaimed() {
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(member(101L, TeamReportStatus.DUE, "ignored")));
        when(occurrenceService.claimInitial(101L, BUSINESS_DATE)).thenReturn(Optional.empty());

        service.runInitialSlot();
        service.runInitialSlot();

        verify(occurrenceService, org.mockito.Mockito.times(2)).claimInitial(101L, BUSINESS_DATE);
        verifyNoInteractions(userRepository, deliveryPort);
        verify(occurrenceService, never()).complete(any(), any());
    }

    @Test
    void shouldNotLogReportEvidenceOrChatDestination() {
        String privateReportContent = "private report content";
        String privateChatId = "90001";
        ReminderClaim claim = new ReminderClaim(605L, 101L, 1);
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(member(101L, TeamReportStatus.DUE, privateReportContent)));
        when(occurrenceService.claimInitial(101L, BUSINESS_DATE)).thenReturn(Optional.of(claim));
        when(userRepository.findByInternalId(101L)).thenReturn(Optional.of(userWithChat(101L, Long.valueOf(privateChatId))));
        when(deliveryPort.deliver(90001L)).thenReturn(ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(ReminderRunService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.runInitialSlot();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(privateReportContent) || message.contains(privateChatId));
    }

    @Test
    void shouldContinueAfterClaimFailureAndLogOnlyBoundedCandidateContext() {
        ReminderRetryCandidate failed = new ReminderRetryCandidate(601L, 101L);
        ReminderRetryCandidate later = new ReminderRetryCandidate(602L, 102L);
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of(failed, later));
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(
                        member(101L, TeamReportStatus.DUE, "ignored"),
                        member(102L, TeamReportStatus.DUE, "ignored")
                ));
        when(occurrenceService.claimRetry(601L, 2))
                .thenThrow(new IllegalStateException(PRIVATE_FAILURE_DETAIL));
        when(occurrenceService.claimRetry(602L, 2)).thenReturn(Optional.empty());

        List<ILoggingEvent> events = captureReminderLogs(service::runFirstRetrySlot);

        verify(occurrenceService).claimRetry(602L, 2);
        assertFailureLogged(events, "claim", "userId=101", "occurrenceId=601", "attempt=2");
    }

    @Test
    void shouldContinueAfterSuppressionFailureAndAlwaysExpireOnceInTheFinalSlot() {
        ReminderRetryCandidate failed = new ReminderRetryCandidate(601L, 101L);
        ReminderRetryCandidate later = new ReminderRetryCandidate(602L, 102L);
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of(failed, later));
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(
                        member(101L, TeamReportStatus.SUBMITTED_ON_TIME, "ignored"),
                        member(102L, TeamReportStatus.SUBMITTED_ON_TIME, "ignored")
                ));
        doThrow(new IllegalStateException(PRIVATE_FAILURE_DETAIL)).when(occurrenceService).suppress(601L);

        List<ILoggingEvent> events = captureReminderLogs(service::runFinalRetrySlot);

        verify(occurrenceService).suppress(602L);
        verify(occurrenceService, times(1)).expireRetryEligible(BUSINESS_DATE);
        assertFailureLogged(events, "suppress", "userId=101", "occurrenceId=601");
    }

    @Test
    void shouldCompleteBoundedUnknownOutcomesAndContinueAfterLookupAndDeliveryFailures() {
        ReminderClaim lookupFailure = new ReminderClaim(601L, 101L, 2);
        ReminderClaim deliveryFailure = new ReminderClaim(602L, 102L, 2);
        ReminderClaim later = new ReminderClaim(603L, 103L, 2);
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of(
                new ReminderRetryCandidate(601L, 101L),
                new ReminderRetryCandidate(602L, 102L),
                new ReminderRetryCandidate(603L, 103L)
        ));
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(
                        member(101L, TeamReportStatus.DUE, "ignored"),
                        member(102L, TeamReportStatus.DUE, "ignored"),
                        member(103L, TeamReportStatus.DUE, "ignored")
                ));
        when(occurrenceService.claimRetry(601L, 2)).thenReturn(Optional.of(lookupFailure));
        when(occurrenceService.claimRetry(602L, 2)).thenReturn(Optional.of(deliveryFailure));
        when(occurrenceService.claimRetry(603L, 2)).thenReturn(Optional.of(later));
        when(userRepository.findByInternalId(101L))
                .thenThrow(new IllegalStateException(PRIVATE_FAILURE_DETAIL));
        when(userRepository.findByInternalId(102L)).thenReturn(Optional.of(userWithChat(102L, 90002L)));
        when(userRepository.findByInternalId(103L)).thenReturn(Optional.of(userWithChat(103L, 90003L)));
        when(deliveryPort.deliver(90002L)).thenThrow(new IllegalStateException(PRIVATE_FAILURE_DETAIL));
        when(deliveryPort.deliver(90003L)).thenReturn(ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);

        List<ILoggingEvent> events = captureReminderLogs(service::runFirstRetrySlot);

        verify(occurrenceService).complete(lookupFailure, ReminderDeliveryOutcome.UNKNOWN_TRANSPORT);
        verify(occurrenceService).complete(deliveryFailure, ReminderDeliveryOutcome.UNKNOWN_TRANSPORT);
        verify(occurrenceService).complete(later, ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);
        assertFailureLogged(events, "lookup", "userId=101", "occurrenceId=601", "attempt=2");
        assertFailureLogged(events, "deliver", "userId=102", "occurrenceId=602", "attempt=2");
        assertThat(events)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("90002"));
    }

    @Test
    void shouldContinueAfterCompletionFailure() {
        ReminderClaim failed = new ReminderClaim(601L, 101L, 2);
        ReminderClaim later = new ReminderClaim(602L, 102L, 2);
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of(
                new ReminderRetryCandidate(601L, 101L),
                new ReminderRetryCandidate(602L, 102L)
        ));
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(
                        member(101L, TeamReportStatus.DUE, "ignored"),
                        member(102L, TeamReportStatus.DUE, "ignored")
                ));
        when(occurrenceService.claimRetry(601L, 2)).thenReturn(Optional.of(failed));
        when(occurrenceService.claimRetry(602L, 2)).thenReturn(Optional.of(later));
        when(userRepository.findByInternalId(101L)).thenReturn(Optional.of(userWithChat(101L, 90001L)));
        when(userRepository.findByInternalId(102L)).thenReturn(Optional.of(userWithChat(102L, 90002L)));
        when(deliveryPort.deliver(anyLong())).thenReturn(ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);
        doThrow(new IllegalStateException(PRIVATE_FAILURE_DETAIL))
                .when(occurrenceService).complete(failed, ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);

        List<ILoggingEvent> events = captureReminderLogs(service::runFirstRetrySlot);

        verify(occurrenceService).complete(later, ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM);
        assertFailureLogged(events, "complete", "userId=101", "occurrenceId=601", "attempt=2");
    }

    @Test
    void shouldContainExpirationFailureAndLogNoPrivateDetails() {
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of());
        doThrow(new IllegalStateException(PRIVATE_FAILURE_DETAIL))
                .when(occurrenceService).expireRetryEligible(BUSINESS_DATE);

        List<ILoggingEvent> events = captureReminderLogs(service::runFinalRetrySlot);

        verify(occurrenceService, times(1)).expireRetryEligible(BUSINESS_DATE);
        assertFailureLogged(events, "expire", "businessDate=2026-07-14");
    }

    @Test
    void shouldRetryOnlyKnownFailureForStillDueMemberAndUseTheApprovedSecondSlot() {
        ReminderRetryCandidate candidate = new ReminderRetryCandidate(601L, 101L);
        ReminderClaim claim = new ReminderClaim(601L, 101L, 2);
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of(candidate));
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(member(101L, TeamReportStatus.DUE, "ignored")));
        when(occurrenceService.claimRetry(601L, 2)).thenReturn(Optional.of(claim));
        when(userRepository.findByInternalId(101L)).thenReturn(Optional.of(userWithChat(101L, 90001L)));
        when(deliveryPort.deliver(90001L)).thenReturn(ReminderDeliveryOutcome.TELEGRAM_REJECTED);

        service.runFirstRetrySlot();

        verify(occurrenceService).claimRetry(601L, 2);
        verify(occurrenceService).complete(claim, ReminderDeliveryOutcome.TELEGRAM_REJECTED);
        verify(occurrenceService, never()).expireRetryEligible(any());
    }

    @Test
    void shouldSuppressRetryWhenMemberIsNoLongerDueAndNeverCallTelegram() {
        ReminderRetryCandidate candidate = new ReminderRetryCandidate(602L, 101L);
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of(candidate));
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(member(101L, TeamReportStatus.SUBMITTED_ON_TIME, "new report")));

        service.runFirstRetrySlot();

        verify(occurrenceService).suppress(602L);
        verify(occurrenceService, never()).claimRetry(anyLong(), anyInt());
        verifyNoInteractions(userRepository, deliveryPort);
    }

    @Test
    void shouldRecordMissingChatWithoutCallingTelegramAndKeepItAsKnownFailure() {
        ReminderClaim claim = new ReminderClaim(603L, 101L, 1);
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(member(101L, TeamReportStatus.DUE, "ignored")));
        when(occurrenceService.claimInitial(101L, BUSINESS_DATE)).thenReturn(Optional.of(claim));
        when(userRepository.findByInternalId(101L)).thenReturn(Optional.of(userWithChat(101L, null)));

        service.runInitialSlot();

        verifyNoInteractions(deliveryPort);
        verify(occurrenceService).complete(claim, ReminderDeliveryOutcome.MISSING_CHAT);
    }

    @Test
    void shouldMakeUnknownTransportOutcomeTerminalAndNeverRetryItAutomatically() {
        ReminderClaim claim = new ReminderClaim(604L, 101L, 1);
        when(teamReportReadService.readTeamSummary(any(), eq(BUSINESS_DATE), eq(EVALUATED_AT), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(summary(member(101L, TeamReportStatus.DUE, "ignored")));
        when(occurrenceService.claimInitial(101L, BUSINESS_DATE)).thenReturn(Optional.of(claim));
        when(userRepository.findByInternalId(101L)).thenReturn(Optional.of(userWithChat(101L, 90001L)));
        when(deliveryPort.deliver(90001L)).thenReturn(ReminderDeliveryOutcome.UNKNOWN_TRANSPORT);

        service.runInitialSlot();
        service.runFirstRetrySlot();

        verify(occurrenceService).complete(claim, ReminderDeliveryOutcome.UNKNOWN_TRANSPORT);
        verify(occurrenceService).findRetryEligible(BUSINESS_DATE);
        verify(deliveryPort).deliver(90001L);
    }

    @Test
    void shouldExpireRemainingRetryableWorkAfterTheFinalSlotWithoutCatchUp() {
        when(occurrenceService.findRetryEligible(BUSINESS_DATE)).thenReturn(List.of());

        service.runFinalRetrySlot();

        verify(occurrenceService).expireRetryEligible(BUSINESS_DATE);
        verifyNoInteractions(teamReportReadService, userRepository, deliveryPort);
    }

    private ReminderProperties approvedProperties() {
        ReminderProperties approved = new ReminderProperties();
        approved.setEnabled(true);
        approved.setDepartment("Engineering");
        approved.setUnit("Platform");
        approved.setTimeZone("Asia/Ho_Chi_Minh");
        approved.setInitialCron("0 30 16 * * MON-FRI");
        approved.setFirstRetryCron("0 35 16 * * MON-FRI");
        approved.setFinalRetryCron("0 50 16 * * MON-FRI");
        return approved;
    }

    private TeamReportSummary summary(TeamMemberReportStatus... members) {
        EnumMap<TeamReportStatus, Long> counts = new EnumMap<>(TeamReportStatus.class);
        for (TeamReportStatus status : TeamReportStatus.values()) {
            counts.put(status, 0L);
        }
        for (TeamMemberReportStatus member : members) {
            counts.compute(member.status(), (status, count) -> count + 1);
        }
        return new TeamReportSummary(
                new TeamScope("Engineering", properties.getUnit()),
                BUSINESS_DATE,
                EVALUATED_AT,
                ZoneId.of("Asia/Ho_Chi_Minh"),
                members.length,
                counts,
                List.of(members)
        );
    }

    private TeamMemberReportStatus member(long userId, TeamReportStatus status, String reportContent) {
        return new TeamMemberReportStatus(
                userId,
                null,
                null,
                "Member " + userId,
                null,
                null,
                "Engineering",
                properties.getUnit(),
                "Member " + userId,
                status,
                null,
                List.of(new TeamReportEvidence(1000L + userId, BUSINESS_DATE, reportContent, BUSINESS_DATE.atTime(9, 0)))
        );
    }

    private User userWithChat(long id, Long chatId) {
        User user = new User();
        user.setId(id);
        user.setChatId(chatId);
        return user;
    }

    private List<ILoggingEvent> captureReminderLogs(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) getLogger(ReminderRunService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void assertFailureLogged(List<ILoggingEvent> events, String stage, String... context) {
        assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("stage=" + stage, "category=IllegalStateException")
                .contains(context));
        assertThat(events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertThat(events)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(PRIVATE_FAILURE_DETAIL));
    }
}
