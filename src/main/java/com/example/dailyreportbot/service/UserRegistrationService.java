package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.entity.UserStatus;
import com.example.dailyreportbot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.api.objects.Contact;

import java.util.Objects;

@Service
/**
 * Dịch vụ đăng ký và xác thực người dùng.
 * Xử lý luồng cấp phép người dùng mới thông qua số điện thoại trên Telegram
 * nhằm đảm bảo tính bảo mật và định danh chính xác.
 */
public class UserRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(UserRegistrationService.class);

    private final UserRepository userRepository;

    public UserRegistrationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserRegistrationStatus registerOrUpdate(
            org.telegram.telegrambots.meta.api.objects.User telegramUser,
            Long chatId
    ) {
        if (telegramUser == null || telegramUser.getId() == null) {
            return UserRegistrationStatus.INVALID_REQUEST;
        }

        User user = userRepository.findByTelegramUserId(telegramUser.getId()).orElse(null);
        return user == null
                ? UserRegistrationStatus.PHONE_REQUIRED
                : refreshTelegramFields(user, telegramUser, chatId);
    }

    @Transactional
    /**
     * Đăng ký người dùng mới qua Telegram bằng số điện thoại.
     * Liên kết số điện thoại được chia sẻ với dữ liệu nhân sự nội bộ (nếu có),
     * và đánh dấu trạng thái xác thực.
     */
    public UserRegistrationStatus registerWithOwnPhoneNumber(
            org.telegram.telegrambots.meta.api.objects.User telegramUser,
            Long chatId,
            Contact contact
    ) {
        if (telegramUser == null
                || telegramUser.getId() == null
                || chatId == null
                || chatId <= 0
                || contact == null
                || !StringUtils.hasText(contact.getPhoneNumber())
                || !Objects.equals(telegramUser.getId(), contact.getUserId())) {
            return UserRegistrationStatus.INVALID_REQUEST;
        }

        Long telegramUserId = telegramUser.getId();
        String phoneNumber = contact.getPhoneNumber().trim();
        User telegramOwner = userRepository.findByTelegramUserId(telegramUserId).orElse(null);
        if (telegramOwner != null) {
            return Objects.equals(telegramOwner.getPhoneNumber(), phoneNumber)
                    ? refreshTelegramFields(telegramOwner, telegramUser, chatId)
                    : UserRegistrationStatus.CONFLICT;
        }

        User phoneOwner = userRepository.findByPhoneNumber(phoneNumber).orElse(null);
        if (phoneOwner != null) {
            if (phoneOwner.getTelegramUserId() != null
                    && !Objects.equals(phoneOwner.getTelegramUserId(), telegramUserId)) {
                return UserRegistrationStatus.CONFLICT;
            }
            if (phoneOwner.getTelegramUserId() == null && phoneOwner.getStatus() == UserStatus.INACTIVE) {
                return UserRegistrationStatus.CONFLICT;
            }
            return refreshTelegramFields(phoneOwner, telegramUser, chatId);
        }

        User user = new User();
        user.setPhoneNumber(phoneNumber);
        applyTelegramFields(user, telegramUser, chatId);
        try {
            userRepository.saveAndFlush(user);
            log.info("New Telegram user registered after phone consent - telegramUserId={}", telegramUserId);
            return UserRegistrationStatus.REGISTERED;
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            markRollbackOnly();
            log.warn("Telegram phone registration conflict - telegramUserId={}", telegramUserId);
            return UserRegistrationStatus.CONFLICT;
        }
    }

    private UserRegistrationStatus refreshTelegramFields(
            User user,
            org.telegram.telegrambots.meta.api.objects.User telegramUser,
            Long chatId
    ) {
        if ((chatId == null || chatId <= 0 || Objects.equals(user.getChatId(), chatId))
                && Objects.equals(user.getTelegramUserId(), telegramUser.getId())
                && Objects.equals(user.getUsername(), telegramUser.getUserName())
                && Objects.equals(user.getFirstName(), telegramUser.getFirstName())) {
            return UserRegistrationStatus.NO_CHANGE;
        }

        applyTelegramFields(user, telegramUser, chatId);
        try {
            userRepository.saveAndFlush(user);
            return UserRegistrationStatus.REFRESHED;
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            markRollbackOnly();
            log.warn("Telegram registration conflict - telegramUserId={}", telegramUser.getId());
            return UserRegistrationStatus.CONFLICT;
        }
    }

    private void applyTelegramFields(
            User user,
            org.telegram.telegrambots.meta.api.objects.User telegramUser,
            Long chatId
    ) {
        user.setTelegramUserId(telegramUser.getId());
        if (chatId != null && chatId > 0) {
            user.setChatId(chatId);
        }
        user.setUsername(telegramUser.getUserName());
        user.setFirstName(telegramUser.getFirstName());
    }

    private void markRollbackOnly() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
