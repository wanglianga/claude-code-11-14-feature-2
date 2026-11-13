package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 切换评审：主口径切换必须走评审。
 * 评审撞上日报锁定时以锁定优先，状态置 CONFLICT_DEFERRED 并写 caliber_switch_conflict，
 * 切换顺延到下一发布周期（effective_date 晚于 service_date）。
 */
@Entity
@Table(name = "caliber_switch_review")
public class CaliberSwitchReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false, length = 32)
    private String fromCaliberCode;

    @Column(nullable = false, length = 32)
    private String toCaliberCode;

    @Column(nullable = false)
    private int toCaliberVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReviewStatus status;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Column(length = 1024)
    private String decisionComment;

    @Column(nullable = false, length = 64)
    private String requestedBy = "manager";

    @Column(length = 64)
    private String decidedBy;

    private LocalDate effectiveDate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant decidedAt;

    private Instant appliedAt;

    protected CaliberSwitchReview() {
    }

    public CaliberSwitchReview(LocalDate serviceDate, String fromCaliberCode, String toCaliberCode,
                               int toCaliberVersion, ReviewStatus status, String reason, String requestedBy) {
        this.serviceDate = serviceDate;
        this.fromCaliberCode = fromCaliberCode;
        this.toCaliberCode = toCaliberCode;
        this.toCaliberVersion = toCaliberVersion;
        this.status = status;
        this.reason = reason;
        this.requestedBy = requestedBy;
    }

    public void decide(ReviewStatus status, String decidedBy, String comment, LocalDate effectiveDate) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionComment = comment;
        this.effectiveDate = effectiveDate;
        this.decidedAt = Instant.now();
        if (status == ReviewStatus.APPLIED) {
            this.appliedAt = this.decidedAt;
        }
    }

    public void markApplied(LocalDate effectiveDate) {
        this.status = ReviewStatus.APPLIED;
        this.effectiveDate = effectiveDate;
        this.appliedAt = Instant.now();
        if (this.decidedAt == null) {
            this.decidedAt = this.appliedAt;
        }
    }

    public Long getId() {
        return id;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public String getFromCaliberCode() {
        return fromCaliberCode;
    }

    public String getToCaliberCode() {
        return toCaliberCode;
    }

    public int getToCaliberVersion() {
        return toCaliberVersion;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
