package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserProfileService {

    private final UserRepository userRepository;

    public UserProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByTelegramUserId(Long telegramUserId) {
        return telegramUserId == null ? Optional.empty() : userRepository.findByTelegramUserId(telegramUserId);
    }
}
