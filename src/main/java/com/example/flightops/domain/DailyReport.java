package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 日报版本（分区表）：每天用同一批事件重算产生新版本；锁定后更正另出一份（CORRECTION），
 * 原版本永不改写，原值/更正值/原因见 report_correction。
 */
@Entity
@Table(name = "daily_report")
@IdClass(DailyReportId.class)
public class DailyReport {

    public enum Kind { ORIGINAL, CORRECTION }
    public enum Status { OPEN, LOCKED }

    /** id 由序列 daily_report_id_seq 在应用侧显式赋值（分区表复合主键）。 */
    @Id
    private Long id;

    @Id
    private LocalDate reportDate;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 16)
    private String status = Status.OPEN.name();

    /** 更正原因（更正版必填）。 */
    @Column(length = 1024)
    private String reason;

    /** 更正基于哪一版。 */
    private Long basedOnId;

    @Column(nullable = false)
    private int totalInstances;

    /** 日报口径分母。 */
    @Column(nullable = false)
    private int counted;

    @Column(nullable = false)
    private int normal;

    /** 日报口径正常率 %。 */
    private BigDecimal rate;

    /** 实时口径分母（已判定班次）。 */
    @Column(nullable = false)
    private int realtimeCounted;

    @Column(nullable = false)
    private int realtimeNormal;

    /** 实时口径正常率 %。 */
    private BigDecimal realtimeRate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lockedAt;

    protected DailyReport() {
    }

    public DailyReport(Long id, LocalDate reportDate, int version, Kind kind, String reason, Long basedOnId) {
        this.id = id;
        this.reportDate = reportDate;
        this.version = version;
        this.kind = kind.name();
        this.reason = reason;
        this.basedOnId = basedOnId;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public int getVersion() {
        return version;
    }

    public Kind getKind() {
        return Kind.valueOf(kind);
    }

    public Status getStatus() {
        return Status.valueOf(status);
    }

    public void lock() {
        this.status = Status.LOCKED.name();
        this.lockedAt = Instant.now();
    }

    public String getReason() {
        return reason;
    }

    public Long getBasedOnId() {
        return basedOnId;
    }

    public int getTotalInstances() {
        return totalInstances;
    }

    public int getCounted() {
        return counted;
    }

    public int getNormal() {
        return normal;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public int getRealtimeCounted() {
        return realtimeCounted;
    }

    public int getRealtimeNormal() {
        return realtimeNormal;
    }

    public BigDecimal getRealtimeRate() {
        return realtimeRate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void fillAggregates(int totalInstances, int counted, int normal, BigDecimal rate,
                               int realtimeCounted, int realtimeNormal, BigDecimal realtimeRate) {
        this.totalInstances = totalInstances;
        this.counted = counted;
        this.normal = normal;
        this.rate = rate;
        this.realtimeCounted = realtimeCounted;
        this.realtimeNormal = realtimeNormal;
        this.realtimeRate = realtimeRate;
    }
}
