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
 * 口径注册库：每套口径的时间戳来源、分母规则与生效版本。
 * 口径定义改动只追加新版本（同 code 下 version 递增），旧版 RETIRED 保留——
 * 已锁定月报按当时绑定的定义版本复现，不追溯改写。
 */
@Entity
@Table(name = "caliber_definition")
public class CaliberDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CaliberStatus status = CaliberStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TimestampSource timestampSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DenominatorRule denominatorRule;

    /** 该口径自有容差（分钟）；null 时回退到全局 app.release-tolerance-minutes。 */
    private Integer toleranceMinutes;

    @Column(nullable = false, length = 2048)
    private String description;

    /** 本版相对上版的变更说明（首版为"首版发布"）。 */
    @Column(length = 1024)
    private String changeSummary;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    private Instant supersededAt;

    @Column(nullable = false, updatable = false, length = 64)
    private String createdBy = "system";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CaliberDefinition() {
    }

    public CaliberDefinition(String code, String name, int version, CaliberStatus status,
                             TimestampSource timestampSource, DenominatorRule denominatorRule,
                             Integer toleranceMinutes, String description, String changeSummary,
                             LocalDate effectiveFrom, String createdBy) {
        this.code = code;
        this.name = name;
        this.version = version;
        this.status = status;
        this.timestampSource = timestampSource;
        this.denominatorRule = denominatorRule;
        this.toleranceMinutes = toleranceMinutes;
        this.description = description;
        this.changeSummary = changeSummary;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
    }

    public void retire() {
        this.status = CaliberStatus.RETIRED;
        this.supersededAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
    }

    public CaliberStatus getStatus() {
        return status;
    }

    public TimestampSource getTimestampSource() {
        return timestampSource;
    }

    public DenominatorRule getDenominatorRule() {
        return denominatorRule;
    }

    public Integer getToleranceMinutes() {
        return toleranceMinutes;
    }

    public String getDescription() {
        return description;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
