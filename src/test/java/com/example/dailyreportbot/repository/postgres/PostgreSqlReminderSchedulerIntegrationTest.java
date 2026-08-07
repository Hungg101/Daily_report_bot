package com.example.dailyreportbot.repository.postgres;

import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.repository.ReminderDeliveryAttemptRepository;
import com.example.dailyreportbot.repository.ReminderOccurrenceRepository;
import com.example.dailyreportbot.repository.UserRepository;
import com.example.dailyreportbot.service.ReminderClaim;
import com.example.dailyreportbot.service.ReminderOccurrenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Import(ReminderOccurrenceService.class)
class PostgreSqlReminderSchedulerIntegrationTest extends PostgreSqlIntegrationTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReminderOccurrenceService occurrenceService;

    @Autowired
    private ReminderOccurrenceRepository occurrenceRepository;

    @Autowired
    private ReminderDeliveryAttemptRepository attemptRepository;

    @Test
    void shouldAllowOnlyOneConcurrentInitialClaimForTheSameUserAndBusinessDate() throws Exception {
        long userId = inNewTransaction(transactionManager, () -> {
            User user = new User();
            user.setPhoneNumber("+8473001");
            user.setTelegramUserId(73001L);
            return userRepository.saveAndFlush(user).getId();
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Optional<ReminderClaim>> claim = () -> {
            ready.countDown();
            start.await(CONCURRENCY_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            return occurrenceService.claimInitial(userId, BUSINESS_DATE);
        };

        Future<Optional<ReminderClaim>> first = executor.submit(claim);
        Future<Optional<ReminderClaim>> second = executor.submit(claim);
        assertThat(ready.await(CONCURRENCY_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Optional<ReminderClaim>> claims = List.of(
                first.get(CONCURRENCY_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS),
                second.get(CONCURRENCY_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
        );

        assertThat(claims).filteredOn(Optional::isPresent).hasSize(1);
        ReminderClaim winner = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        inNewTransaction(transactionManager, () -> {
            assertThat(occurrenceRepository.count()).isEqualTo(1);
            assertThat(attemptRepository.findByOccurrence_IdAndAttemptNumber(winner.occurrenceId(), 1)).isPresent();
            return null;
        });
    }
}
