package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** 更正记录（分区表）：日报锁定后更正另出一份，原值、更正值与原因同时可查。 */
@Entity
@Table(name = "report_correction")
public class ReportCorrection {

    @EmbeddedId
    private ReportCorrectionId id;

    @Column(length = 64)
    private String oldValue;

    @Column(length = 64)
    private String newValue;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ReportCorrection() {
    }

    public ReportCorrection(ReportCorrectionId id, String oldValue, String newValue, String reason) {
        this.id = id;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    public ReportCorrectionId getId() {
        return id;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
