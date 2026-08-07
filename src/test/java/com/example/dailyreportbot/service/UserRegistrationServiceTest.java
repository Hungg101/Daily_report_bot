package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.IdentityAuditEventRepository;
import com.example.dailyreportbot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.telegram.telegrambots.meta.api.objects.Contact;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserRegistrationServiceTest {

    private UserRepository repository;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        service = new UserRegistrationService(repository);
    }

    @Test
    void shouldRequirePhoneBeforeRegisteringNewTelegramIdentity() {
        org.telegram.telegrambots.meta.api.objects.User telegramUser = telegramUser();
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.empty());

        assertThat(service.registerOrUpdate(telegramUser, 1001L))
                .isEqualTo(UserRegistrationStatus.PHONE_REQUIRED);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRegisterTelegramIdentityFromSendersOwnContact() {
        org.telegram.telegrambots.meta.api.objects.User telegramUser = telegramUser();
        Contact contact = contact(12345L, " +84901234567 ");
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.empty());
        when(repository.findByPhoneNumber("+84901234567")).thenReturn(Optional.empty());

        assertThat(service.registerWithOwnPhoneNumber(telegramUser, 1001L, contact))
                .isEqualTo(UserRegistrationStatus.REGISTERED);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo("+84901234567");
        assertThat(captor.getValue().getTelegramUserId()).isEqualTo(12345L);
        assertThat(captor.getValue().getChatId()).isEqualTo(1001L);
        assertThat(captor.getValue().getUsername()).isEqualTo("daily_user");
    }

    @Test
    void shouldRefreshExistingTelegramIdentityWithoutChangingPhone() {
        User existingUser = registeredUser(1L, "+84901234567");
        existingUser.setUsername("old_username");
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.of(existingUser));

        assertThat(service.registerOrUpdate(telegramUser(), 2002L))
                .isEqualTo(UserRegistrationStatus.REFRESHED);

        verify(repository).saveAndFlush(existingUser);
        assertThat(existingUser.getPhoneNumber()).isEqualTo("+84901234567");
        assertThat(existingUser.getChatId()).isEqualTo(2002L);
        assertThat(existingUser.getUsername()).isEqualTo("daily_user");
        assertThat(existingUser.getFirstName()).isEqualTo("An");
    }

    @Test
    void shouldRefreshPublicLabelsWithoutClearingPrivateDeliveryDestination() {
        User existingUser = registeredUser(1L, "+84901234567");
        existingUser.setChatId(1001L);
        existingUser.setUsername("old_username");
        existingUser.setFirstName("Old name");
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.of(existingUser));

        assertThat(service.registerOrUpdate(telegramUser(), null))
                .isEqualTo(UserRegistrationStatus.REFRESHED);

        verify(repository).saveAndFlush(existingUser);
        assertThat(existingUser.getChatId()).isEqualTo(1001L);
        assertThat(existingUser.getUsername()).isEqualTo("daily_user");
        assertThat(existingUser.getFirstName()).isEqualTo("An");
    }

    @Test
    void shouldRefreshOnlyTelegramFieldsWithoutOverwritingGovernedOrAuditData() {
        User existingUser = registeredUser(1L, "+84901234567");
        existingUser.setEmployeeCode("EMP-01");
        existingUser.setFullName("Nguyen Van A");
        existingUser.setDepartmentName("Engineering");
        existingUser.setUnitName("Delivery");
        existingUser.setStatus(UserStatus.INACTIVE);
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.of(existingUser));

        service.registerOrUpdate(telegramUser(), 2002L);

        assertThat(existingUser.getEmployeeCode()).isEqualTo("EMP-01");
        assertThat(existingUser.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(existingUser.getDepartmentName()).isEqualTo("Engineering");
        assertThat(existingUser.getUnitName()).isEqualTo("Delivery");
        assertThat(existingUser.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(Arrays.stream(UserRegistrationService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().equals(IdentityAuditEventRepository.class)))
                .isTrue();
    }

    @Test
    void shouldRejectAnotherPersonsContact() {
        assertThat(service.registerWithOwnPhoneNumber(
                telegramUser(),
                1001L,
                contact(99999L, "+84901234567")
        )).isEqualTo(UserRegistrationStatus.INVALID_REQUEST);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectPhoneNumberOwnedByAnotherTelegramUser() {
        User owner = registeredUser(2L, "+84901234567");
        owner.setTelegramUserId(99999L);
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.empty());
        when(repository.findByPhoneNumber("+84901234567")).thenReturn(Optional.of(owner));

        assertThat(service.registerWithOwnPhoneNumber(
                telegramUser(),
                1001L,
                contact(12345L, "+84901234567")
        )).isEqualTo(UserRegistrationStatus.CONFLICT);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectInactivePreProvisionedPhoneClaimWithoutPartialMutation() {
        User inactiveOwner = new User();
        inactiveOwner.setId(7L);
        inactiveOwner.setPhoneNumber("+84901234567");
        inactiveOwner.setUsername("provisioned_username");
        inactiveOwner.setFirstName("Provisioned name");
        inactiveOwner.setStatus(UserStatus.INACTIVE);
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.empty());
        when(repository.findByPhoneNumber("+84901234567")).thenReturn(Optional.of(inactiveOwner));

        assertThat(service.registerWithOwnPhoneNumber(
                telegramUser(),
                1001L,
                contact(12345L, "+84901234567")
        )).isEqualTo(UserRegistrationStatus.CONFLICT);

        assertThat(inactiveOwner.getTelegramUserId()).isNull();
        assertThat(inactiveOwner.getChatId()).isNull();
        assertThat(inactiveOwner.getUsername()).isEqualTo("provisioned_username");
        assertThat(inactiveOwner.getFirstName()).isEqualTo("Provisioned name");
        assertThat(inactiveOwner.getStatus()).isEqualTo(UserStatus.INACTIVE);
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectDifferentPhoneForRegisteredTelegramUser() {
        User existing = registeredUser(1L, "+84901111111");
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.of(existing));

        assertThat(service.registerWithOwnPhoneNumber(
                telegramUser(),
                1001L,
                contact(12345L, "+84902222222")
        )).isEqualTo(UserRegistrationStatus.CONFLICT);

        assertThat(existing.getPhoneNumber()).isEqualTo("+84901111111");
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldTreatRepeatedOwnContactAsIdempotent() {
        User existing = registeredUser(1L, "+84901234567");
        existing.setChatId(1001L);
        existing.setUsername("daily_user");
        existing.setFirstName("An");
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.of(existing));

        assertThat(service.registerWithOwnPhoneNumber(
                telegramUser(),
                1001L,
                contact(12345L, "+84901234567")
        )).isEqualTo(UserRegistrationStatus.NO_CHANGE);

        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldIgnoreMissingTelegramIdentity() {
        assertThat(service.registerOrUpdate(null, 1001L))
                .isEqualTo(UserRegistrationStatus.INVALID_REQUEST);

        verifyNoInteractions(repository);
    }

    @Test
    void shouldReturnConflictWhenRegistrationRefreshLosesOptimisticRace() {
        User existing = registeredUser(1L, "+84901234567");
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenThrow(
                new ObjectOptimisticLockingFailureException(User.class, "+84901234567")
        );

        assertThat(service.registerOrUpdate(telegramUser(), 2002L))
                .isEqualTo(UserRegistrationStatus.CONFLICT);
    }

    @Test
    void shouldReturnConflictWhenPhoneClaimLosesDatabaseRace() {
        when(repository.findByTelegramUserId(12345L)).thenReturn(Optional.empty());
        when(repository.findByPhoneNumber("+84901234567")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(User.class)))
                .thenThrow(new DataIntegrityViolationException("phone race"));

        assertThat(service.registerWithOwnPhoneNumber(
                telegramUser(),
                1001L,
                contact(12345L, "+84901234567")
        )).isEqualTo(UserRegistrationStatus.CONFLICT);
    }

    private User registeredUser(Long id, String phoneNumber) {
        User user = new User();
        user.setId(id);
        user.setPhoneNumber(phoneNumber);
        user.setTelegramUserId(12345L);
        return user;
    }

    private Contact contact(Long telegramUserId, String phoneNumber) {
        Contact contact = mock(Contact.class);
        when(contact.getUserId()).thenReturn(telegramUserId);
        when(contact.getPhoneNumber()).thenReturn(phoneNumber);
        return contact;
    }

    private org.telegram.telegrambots.meta.api.objects.User telegramUser() {
        org.telegram.telegrambots.meta.api.objects.User telegramUser = mock(
                org.telegram.telegrambots.meta.api.objects.User.class
        );
        when(telegramUser.getId()).thenReturn(12345L);
        when(telegramUser.getUserName()).thenReturn("daily_user");
        when(telegramUser.getFirstName()).thenReturn("An");
        return telegramUser;
    }
}
