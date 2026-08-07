package com.example.dailyreportbot.repository;

import com.example.dailyreportbot.entity.IdentityAuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IdentityAuditEventRepository {

    private static final int MAX_HISTORY_LIMIT = 100;

    @PersistenceContext
    private EntityManager entityManager;

    public IdentityAuditEvent append(IdentityAuditEvent event) {
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }

    public List<IdentityAuditEvent> findHistory(Long targetUserId, int limit) {
        if (targetUserId == null || limit <= 0) {
            return List.of();
        }

        return entityManager.createQuery(
                        """
                        SELECT event
                        FROM IdentityAuditEvent event
                        WHERE event.targetUser.id = :targetUserId
                           OR event.relatedUser.id = :targetUserId
                        ORDER BY event.createdAt DESC, event.id DESC
                        """,
                        IdentityAuditEvent.class
                )
                .setParameter("targetUserId", targetUserId)
                .setMaxResults(Math.min(limit, MAX_HISTORY_LIMIT))
                .getResultList();
    }
}
