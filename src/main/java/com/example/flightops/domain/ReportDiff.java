package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** 版本间差异（分区表）：逐航班比对放行状态与延误分钟，超阈值定位到具体那次变更。 */
@Entity
@Table(name = "report_diff")
public class ReportDiff {

    @EmbeddedId
    private ReportDiffId id;

    @Column(length = 64)
    private String oldValue;

    @Column(length = 64)
    private String newValue;

    @Column(nullable = false)
    private boolean exceedsThreshold;

    /** 定位到具体变更：该实例在两版之间的 change_version 列表，如 "5,6"。 */
    @Column(length = 256)
    private String causedByChanges;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ReportDiff() {
    }

    public ReportDiff(ReportDiffId id, String oldValue, String newValue,
                      boolean exceedsThreshold, String causedByChanges) {
        this.id = id;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.exceedsThreshold = exceedsThreshold;
        this.causedByChanges = causedByChanges;
    }

    public ReportDiffId getId() {
        return id;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public boolean isExceedsThreshold() {
        return exceedsThreshold;
    }

    public String getCausedByChanges() {
        return causedByChanges;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
