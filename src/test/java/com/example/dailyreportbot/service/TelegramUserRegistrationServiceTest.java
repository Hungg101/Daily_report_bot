package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.TelegramUser;
import com.example.dailyreportbot.repository.TelegramUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramUserRegistrationServiceTest {

    private final TelegramUserRepository repository = mock(TelegramUserRepository.class);
    private final TelegramUserRegistrationService service = new TelegramUserRegistrationService(repository);

    @Test
    void shouldSaveNewTelegramUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);
        when(user.getUserName()).thenReturn("alice");
        when(user.getFirstName()).thenReturn("Alice");
        when(repository.existsByTelegramUserId(12345L)).thenReturn(false);

        service.registerIfNew(user);

        ArgumentCaptor<TelegramUser> captor = ArgumentCaptor.forClass(TelegramUser.class);
        verify(repository).save(captor.capture());

        TelegramUser savedUser = captor.getValue();
        assertThat(savedUser.getTelegramUserId()).isEqualTo(12345L);
        assertThat(savedUser.getUsername()).isEqualTo("alice");
        assertThat(savedUser.getFirstName()).isEqualTo("Alice");
    }

    @Test
    void shouldNotSaveExistingTelegramUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(12345L);
        when(repository.existsByTelegramUserId(12345L)).thenReturn(true);

        service.registerIfNew(user);

        verify(repository, never()).save(any(TelegramUser.class));
    }
}
