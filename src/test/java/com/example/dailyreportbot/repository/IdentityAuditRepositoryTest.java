package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.IdentityAdminAction;
import com.example.dailyreportbot.entity.IdentityAuditEvent;
import com.example.dailyreportbot.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(IdentityAuditEventRepository.class)
class IdentityAuditRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private IdentityAuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAppendAndReadBoundedHistoryNewestFirst() {
        User user = entityManager.persist(user("+84910000001"));
        LocalDateTime base = LocalDateTime.of(2026, 7, 2, 9, 0);
        repository.append(event(IdentityAdminAction.CREATE, user, "create", base));
        repository.append(event(IdentityAdminAction.UPDATE_PROFILE, user, "update", base.plusMinutes(1)));
        repository.append(event(IdentityAdminAction.DEACTIVATE, user, "deactivate", base.plusMinutes(2)));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findHistory(user.getId(), 2))
                .extracting(IdentityAuditEvent::getAction)
                .containsExactly(IdentityAdminAction.DEACTIVATE, IdentityAdminAction.UPDATE_PROFILE);
        assertThat(repository.findHistory(user.getId(), 0)).isEmpty();
    }

    @Test
    void shouldTreatLoadedAuditEventsAsImmutable() {
        User user = entityManager.persist(user("+84910000002"));
        IdentityAuditEvent saved = repository.append(event(
                IdentityAdminAction.CREATE,
                user,
                "create",
                LocalDateTime.of(2026, 7, 2, 9, 0)
        ));
        entityManager.flush();
        entityManager.clear();

        IdentityAuditEvent loaded = repository.findHistory(user.getId(), 1).get(0);
        ReflectionTestUtils.setField(loaded, "actor", "changed actor");
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor FROM identity_admin_audit_events WHERE id = ?",
                String.class,
                saved.getId()
        )).isEqualTo("trusted-internal-caller");
        assertThat(Arrays.stream(IdentityAuditEventRepository.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("delete", "deleteById", "save");
    }

    private User user(String phoneNumber) {
        User user = new User();
        user.setPhoneNumber(phoneNumber);
        return user;
    }

    private IdentityAuditEvent event(
            IdentityAdminAction action,
            User target,
            String reason,
            LocalDateTime createdAt
    ) {
        return new IdentityAuditEvent(
                action,
                "trusted-internal-caller",
                target,
                null,
                reason,
                "{}",
                "{status=ACTIVE}",
                createdAt
        );
    }
}
