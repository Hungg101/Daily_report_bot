package com.example.dailyreportbot.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class UserModelTest {

    @Test
    void shouldDefaultToActiveAndSetTimestampsBeforePersist() {
        User user = new User();

        user.prePersist();

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getVersion()).isZero();
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.isManager()).isFalse();
    }

    @Test
    void shouldRefreshOnlyUpdatedTimestampBeforeUpdate() {
        User user = new User();
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 8, 0);
        LocalDateTime previousUpdatedAt = LocalDateTime.of(2026, 1, 2, 8, 0);
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(previousUpdatedAt);

        user.preUpdate();

        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isAfter(previousUpdatedAt);
    }
}
