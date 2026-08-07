package com.example.dailyreportbot.repository.postgres;

import com.example.dailyreportbot.repository.UserRepository;
import com.example.dailyreportbot.service.UserRegistrationService;
import com.example.dailyreportbot.service.UserRegistrationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Contact;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlPhoneRegistrationIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private UserRegistrationService registrationService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void concurrentPhoneRegistrationsProduceOneSuccessAndOneConflict() throws Exception {
        Contact firstContact = contact(4201L, "+84909990000");
        Contact secondContact = contact(4202L, "+84909990000");

        var first = executor.submit(() -> registrationService.registerWithOwnPhoneNumber(
                telegramUser(4201L), 4201L, firstContact
        ));
        var second = executor.submit(() -> registrationService.registerWithOwnPhoneNumber(
                telegramUser(4202L), 4202L, secondContact
        ));

        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
                UserRegistrationStatus.REGISTERED,
                UserRegistrationStatus.CONFLICT
        );
        assertThat(userRepository.findByPhoneNumber("+84909990000")).isPresent();
    }

    private Contact contact(long telegramUserId, String phoneNumber) {
        Contact contact = new Contact();
        contact.setUserId(telegramUserId);
        contact.setPhoneNumber(phoneNumber);
        contact.setFirstName("Claimant");
        return contact;
    }

    private org.telegram.telegrambots.meta.api.objects.User telegramUser(long telegramUserId) {
        org.telegram.telegrambots.meta.api.objects.User user = new org.telegram.telegrambots.meta.api.objects.User();
        user.setId(telegramUserId);
        user.setFirstName("Claimant");
        return user;
    }
}
