package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.ReminderDeliveryAttempt;
import com.example.dailyreportbot.entity.ReminderDeliveryAttemptState;
import com.example.dailyreportbot.entity.ReminderOccurrence;
import com.example.dailyreportbot.entity.ReminderOccurrenceState;
import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.repository.ReminderDeliveryAttemptRepository;
import com.example.dailyreportbot.repository.ReminderOccurrenceRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class ReminderOccurrenceService {

    private static final String UNKNOWN_TRANSPORT = ReminderDeliveryOutcome.UNKNOWN_TRANSPORT.name();
    private static final String NO_LONGER_DUE = "NO_LONGER_DUE";
    private static final String FINAL_SLOT_EXPIRED = "FINAL_SLOT_EXPIRED";

    private final UserRepository userRepository;
    private final ReminderOccurrenceRepository occurrenceRepository;
    private final ReminderDeliveryAttemptRepository attemptRepository;

    public ReminderOccurrenceService(
            UserRepository userRepository,
            ReminderOccurrenceRepository occurrenceRepository,
            ReminderDeliveryAttemptRepository attemptRepository
    ) {
        this.userRepository = userRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.attemptRepository = attemptRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ReminderClaim> claimInitial(long userId, LocalDate businessDate) {
        User user = userRepository.findLockedById(userId).orElse(null);
        if (user == null || occurrenceRepository.findLockedByUserIdAndBusinessDate(userId, businessDate).isPresent()) {
            return Optional.empty();
        }

        ReminderOccurrence occurrence = new ReminderOccurrence();
        occurrence.setUser(user);
        occurrence.setBusinessDate(businessDate);
        markUnknownBeforeExternalDelivery(occurrence);
        ReminderOccurrence savedOccurrence = occurrenceRepository.saveAndFlush(occurrence);
        createUnknownAttempt(savedOccurrence, 1);
        return Optional.of(new ReminderClaim(savedOccurrence.getId(), userId, 1));
    }

    @Transactional(readOnly = true)
    public List<ReminderRetryCandidate> findRetryEligible(LocalDate businessDate) {
        return occurrenceRepository.findByBusinessDateAndStateOrderByIdAsc(
                        businessDate,
                        ReminderOccurrenceState.RETRY_ELIGIBLE
                ).stream()
                .map(occurrence -> new ReminderRetryCandidate(occurrence.getId(), occurrence.getUser().getId()))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ReminderClaim> claimRetry(long occurrenceId, int attemptNumber) {
        ReminderOccurrence occurrence = occurrenceRepository.findLockedById(occurrenceId).orElse(null);
        if (occurrence == null || occurrence.getState() != ReminderOccurrenceState.RETRY_ELIGIBLE) {
            return Optional.empty();
        }
        if (attemptRepository.findByOccurrence_IdAndAttemptNumber(occurrenceId, attemptNumber).isPresent()) {
            return Optional.empty();
        }

        markUnknownBeforeExternalDelivery(occurrence);
        createUnknownAttempt(occurrence, attemptNumber);
        return Optional.of(new ReminderClaim(occurrenceId, occurrence.getUser().getId(), attemptNumber));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(ReminderClaim claim, ReminderDeliveryOutcome outcome) {
        ReminderOccurrence occurrence = occurrenceRepository.findLockedById(claim.occurrenceId()).orElse(null);
        if (occurrence == null) {
            return;
        }
        ReminderDeliveryAttempt attempt = attemptRepository
                .findByOccurrence_IdAndAttemptNumber(claim.occurrenceId(), claim.attemptNumber())
                .orElse(null);
        if (attempt == null || attempt.getState() != ReminderDeliveryAttemptState.UNKNOWN) {
            return;
        }

        if (outcome == ReminderDeliveryOutcome.ACCEPTED_BY_TELEGRAM) {
            attempt.setState(ReminderDeliveryAttemptState.DELIVERED);
            attempt.setFailureCategory(null);
            occurrence.setState(ReminderOccurrenceState.DELIVERED);
            occurrence.setTerminalReason(null);
            return;
        }

        if (outcome == ReminderDeliveryOutcome.MISSING_CHAT || outcome == ReminderDeliveryOutcome.TELEGRAM_REJECTED) {
            attempt.setState(ReminderDeliveryAttemptState.KNOWN_FAILURE);
            attempt.setFailureCategory(outcome.name());
            boolean finalAttempt = claim.attemptNumber() == 3;
            occurrence.setState(finalAttempt ? ReminderOccurrenceState.FAILED : ReminderOccurrenceState.RETRY_ELIGIBLE);
            occurrence.setTerminalReason(finalAttempt ? outcome.name() : null);
            return;
        }

        attempt.setState(ReminderDeliveryAttemptState.UNKNOWN);
        attempt.setFailureCategory(UNKNOWN_TRANSPORT);
        occurrence.setState(ReminderOccurrenceState.UNKNOWN);
        occurrence.setTerminalReason(UNKNOWN_TRANSPORT);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suppress(long occurrenceId) {
        occurrenceRepository.findLockedById(occurrenceId)
                .filter(occurrence -> occurrence.getState() == ReminderOccurrenceState.RETRY_ELIGIBLE)
                .ifPresent(occurrence -> {
                    occurrence.setState(ReminderOccurrenceState.SUPPRESSED);
                    occurrence.setTerminalReason(NO_LONGER_DUE);
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireRetryEligible(LocalDate businessDate) {
        List<Long> ids = occurrenceRepository.findByBusinessDateAndStateOrderByIdAsc(
                        businessDate,
                        ReminderOccurrenceState.RETRY_ELIGIBLE
                ).stream()
                .map(ReminderOccurrence::getId)
                .toList();
        for (Long id : ids) {
            occurrenceRepository.findLockedById(id)
                    .filter(occurrence -> occurrence.getState() == ReminderOccurrenceState.RETRY_ELIGIBLE)
                    .ifPresent(occurrence -> {
                        occurrence.setState(ReminderOccurrenceState.EXPIRED);
                        occurrence.setTerminalReason(FINAL_SLOT_EXPIRED);
                    });
        }
    }

    private void createUnknownAttempt(ReminderOccurrence occurrence, int attemptNumber) {
        ReminderDeliveryAttempt attempt = new ReminderDeliveryAttempt();
        attempt.setOccurrence(occurrence);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setState(ReminderDeliveryAttemptState.UNKNOWN);
        attemptRepository.saveAndFlush(attempt);
    }

    private void markUnknownBeforeExternalDelivery(ReminderOccurrence occurrence) {
        occurrence.setState(ReminderOccurrenceState.UNKNOWN);
        occurrence.setTerminalReason(UNKNOWN_TRANSPORT);
    }
}
