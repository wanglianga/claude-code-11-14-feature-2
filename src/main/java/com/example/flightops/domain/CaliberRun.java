package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 口径物化运行：同一批变更事件，每个口径各产出一份带版本的结果。
 * 结果行内快照口径定义（timestamp_source/denominator_rule/tolerance）并绑定 caliber_version，
 * 口径定义后续改动不影响本运行的复现——口径定义与结果双版本绑定。
 */
@Entity
@Table(name = "caliber_run")
@IdClass(CaliberRunId.class)
public class CaliberRun {

    @Id
    private Long id;

    @Id
    private LocalDate serviceDate;

    @Column(nullable = false, length = 32)
    private String caliberCode;

    @Column(nullable = false)
    private int caliberVersion;

    /** 同口径同日期内递增的结果版本。 */
    @Column(nullable = false)
    private int runNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CaliberRunStatus status = CaliberRunStatus.COMPUTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TimestampSource timestampSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DenominatorRule denominatorRule;

    @Column(nullable = false)
    private int toleranceMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackfillScope backfillScope = BackfillScope.ORIGINAL;

    @Column(nullable = false)
    private int totalInstances;

    @Column(nullable = false)
    private int counted;

    @Column(nullable = false)
    private int normal;

    private BigDecimal rate;

    @Column(length = 512)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant publishedAt;

    protected CaliberRun() {
    }

    public CaliberRun(Long id, LocalDate serviceDate, String caliberCode, int caliberVersion, int runNo,
                      TimestampSource timestampSource, DenominatorRule denominatorRule, int toleranceMinutes,
                      BackfillScope backfillScope, String reason) {
        this.id = id;
        this.serviceDate = serviceDate;
        this.caliberCode = caliberCode;
        this.caliberVersion = caliberVersion;
        this.runNo = runNo;
        this.timestampSource = timestampSource;
        this.denominatorRule = denominatorRule;
        this.toleranceMinutes = toleranceMinutes;
        this.backfillScope = backfillScope;
        this.reason = reason;
    }

    public void fillAggregates(int totalInstances, int counted, int normal, BigDecimal rate) {
        this.totalInstances = totalInstances;
        this.counted = counted;
        this.normal = normal;
        this.rate = rate;
    }

    public void markPublished() {
        this.status = CaliberRunStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void markSuperseded() {
        this.status = CaliberRunStatus.SUPERSEDED;
    }

    public void markBlocked() {
        this.status = CaliberRunStatus.BLOCKED;
    }

    /** 运行内含补齐明细时，把运行级区间标注从 ORIGINAL 提升为 BACKFILL（与原始区间分列）。 */
    public void markBackfillScope() {
        this.backfillScope = BackfillScope.BACKFILL;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public String getCaliberCode() {
        return caliberCode;
    }

    public int getCaliberVersion() {
        return caliberVersion;
    }

    public int getRunNo() {
        return runNo;
    }

    public CaliberRunStatus getStatus() {
        return status;
    }

    public TimestampSource getTimestampSource() {
        return timestampSource;
    }

    public DenominatorRule getDenominatorRule() {
        return denominatorRule;
    }

    public int getToleranceMinutes() {
        return toleranceMinutes;
    }

    public BackfillScope getBackfillScope() {
        return backfillScope;
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

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
