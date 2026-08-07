package com.example.dailyreportbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Immutable
@Table(name = "identity_admin_audit_events")
public class IdentityAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private IdentityAdminAction action;

    @Column(name = "actor", nullable = false, length = 255, updatable = false)
    private String actor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "target_user_id",
            referencedColumnName = "id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_identity_admin_audit_target_user")
    )
    private User targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "related_user_id",
            referencedColumnName = "id",
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_identity_admin_audit_related_user")
    )
    private User relatedUser;

    @Column(name = "reason", nullable = false, length = 1000, updatable = false)
    private String reason;

    @Column(name = "before_state", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String beforeState;

    @Column(name = "after_state", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String afterState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected IdentityAuditEvent() {
    }

    public IdentityAuditEvent(
            IdentityAdminAction action,
            String actor,
            User targetUser,
            User relatedUser,
            String reason,
            String beforeState,
            String afterState,
            LocalDateTime createdAt
    ) {
        this.action = action;
        this.actor = actor;
        this.targetUser = targetUser;
        this.relatedUser = relatedUser;
        this.reason = reason;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public IdentityAdminAction getAction() { return action; }
    public String getActor() { return actor; }
    public User getTargetUser() { return targetUser; }
    public User getRelatedUser() { return relatedUser; }
    public String getReason() { return reason; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
