package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.ReminderDeliveryAttempt;
import com.example.dailyreportbot.entity.ReminderDeliveryAttemptState;
import com.example.dailyreportbot.entity.ReminderOccurrence;
import com.example.dailyreportbot.entity.ReminderOccurrenceState;
import com.example.dailyreportbot.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reminder-occurrence-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReminderOccurrenceRepositoryTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReminderOccurrenceRepository occurrenceRepository;

    @Autowired
    private ReminderDeliveryAttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistOneOccurrenceAndUpToThreeUniquelyNumberedAttemptsWithoutTrackingTimestamps() {
        ReminderOccurrence occurrence = occurrenceRepository.saveAndFlush(newOccurrence(savedUser(71001L)));
        ReminderDeliveryAttempt attempt = attemptRepository.saveAndFlush(newAttempt(occurrence, 1));

        assertThat(occurrence.getId()).isPositive();
        assertThat(attempt.getId()).isPositive();
        assertThat(occurrence.getBusinessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(attempt.getState()).isEqualTo(ReminderDeliveryAttemptState.UNKNOWN);
        assertThat(columnNames("reminder_occurrences")).doesNotContain("created_at", "updated_at", "occurred_at");
        assertThat(columnNames("reminder_delivery_attempts")).doesNotContain("created_at", "updated_at", "attempted_at");
    }

    @Test
    void shouldEnforceOccurrenceAndAttemptUniquenessAndForeignKeys() {
        User user = savedUser(71002L);
        ReminderOccurrence occurrence = occurrenceRepository.saveAndFlush(newOccurrence(user));
        attemptRepository.saveAndFlush(newAttempt(occurrence, 1));

        assertThatThrownBy(() -> occurrenceRepository.saveAndFlush(newOccurrence(user)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO reminder_delivery_attempts (occurrence_id, attempt_number, state) VALUES (999999, 1, 'UNKNOWN')"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRestrictAttemptNumberToApprovedSlots() {
        ReminderOccurrence occurrence = occurrenceRepository.saveAndFlush(newOccurrence(savedUser(71003L)));

        assertThatThrownBy(() -> attemptRepository.saveAndFlush(newAttempt(occurrence, 4)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User savedUser(long telegramUserId) {
        User user = new User();
        user.setPhoneNumber("+84" + telegramUserId);
        user.setTelegramUserId(telegramUserId);
        return userRepository.saveAndFlush(user);
    }

    private ReminderOccurrence newOccurrence(User user) {
        ReminderOccurrence occurrence = new ReminderOccurrence();
        occurrence.setUser(user);
        occurrence.setBusinessDate(BUSINESS_DATE);
        occurrence.setState(ReminderOccurrenceState.PENDING);
        return occurrence;
    }

    private ReminderDeliveryAttempt newAttempt(ReminderOccurrence occurrence, int attemptNumber) {
        ReminderDeliveryAttempt attempt = new ReminderDeliveryAttempt();
        attempt.setOccurrence(occurrence);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setState(ReminderDeliveryAttemptState.UNKNOWN);
        return attempt;
    }

    private List<String> columnNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                String.class,
                tableName
        );
    }
}
