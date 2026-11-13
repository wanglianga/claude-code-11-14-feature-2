package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** 切换冲突记录：切换评审撞上日报锁定，锁定优先，切换顺延到下一发布周期。 */
@Entity
@Table(name = "caliber_switch_conflict")
public class CaliberSwitchConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reviewId;

    @Column(nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false)
    private Long lockedReportId;

    @Column(nullable = false)
    private Instant lockedAt;

    @Column(nullable = false)
    private LocalDate deferredToDate;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Column(nullable = false)
    private boolean resolved = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CaliberSwitchConflict() {
    }

    public CaliberSwitchConflict(Long reviewId, LocalDate serviceDate, Long lockedReportId, Instant lockedAt,
                                 LocalDate deferredToDate, String reason) {
        this.reviewId = reviewId;
        this.serviceDate = serviceDate;
        this.lockedReportId = lockedReportId;
        this.lockedAt = lockedAt;
        this.deferredToDate = deferredToDate;
        this.reason = reason;
    }

    public void resolve() {
        this.resolved = true;
    }

    public Long getId() {
        return id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public Long getLockedReportId() {
        return lockedReportId;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public LocalDate getDeferredToDate() {
        return deferredToDate;
    }

    public String getReason() {
        return reason;
    }

    public boolean isResolved() {
        return resolved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
