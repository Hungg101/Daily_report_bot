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
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "reminder_delivery_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reminder_delivery_attempts_occurrence_number",
                columnNames = {"occurrence_id", "attempt_number"}
        )
)
public class ReminderDeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "occurrence_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_reminder_delivery_attempts_occurrence")
    )
    private ReminderOccurrence occurrence;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ReminderDeliveryAttemptState state;

    @Column(name = "failure_category", length = 64)
    private String failureCategory;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ReminderOccurrence getOccurrence() { return occurrence; }
    public void setOccurrence(ReminderOccurrence occurrence) { this.occurrence = occurrence; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public ReminderDeliveryAttemptState getState() { return state; }
    public void setState(ReminderDeliveryAttemptState state) { this.state = state; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }
}
