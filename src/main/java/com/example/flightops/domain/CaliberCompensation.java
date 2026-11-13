package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 补偿留痕：超窗补录的落地时间（以及窗口内更新）必须向所有并行口径逐口径补偿。
 * 只补主口径会让两套结果在某个分区悄悄分叉——每个口径一行，补偿链路完整可查。
 */
@Entity
@Table(name = "caliber_compensation")
@IdClass(CaliberCompensationId.class)
public class CaliberCompensation {

    @Id
    private Long id;

    @Id
    private LocalDate serviceDate;

    @Column(nullable = false, length = 32)
    private String caliberCode;

    @Column(nullable = false)
    private int caliberVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CompensationKind kind;

    @Column(nullable = false, length = 10)
    private String flightNo;

    @Column(nullable = false)
    private int legNo;

    private Long fromRunId;

    private Long toRunId;

    /** 触发补偿的变更版本号列表，如 "12,13"。 */
    @Column(nullable = false, length = 256)
    private String triggerChangeVersions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChangeSource source;

    @Column(nullable = false)
    private boolean late;

    @Column(length = 512)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CaliberCompensation() {
    }

    public CaliberCompensation(Long id, LocalDate serviceDate, String caliberCode, int caliberVersion,
                               CompensationKind kind, String flightNo, int legNo, Long fromRunId, Long toRunId,
                               String triggerChangeVersions, ChangeSource source, boolean late, String note) {
        this.id = id;
        this.serviceDate = serviceDate;
        this.caliberCode = caliberCode;
        this.caliberVersion = caliberVersion;
        this.kind = kind;
        this.flightNo = flightNo;
        this.legNo = legNo;
        this.fromRunId = fromRunId;
        this.toRunId = toRunId;
        this.triggerChangeVersions = triggerChangeVersions;
        this.source = source;
        this.late = late;
        this.note = note;
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

    public CompensationKind getKind() {
        return kind;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public int getLegNo() {
        return legNo;
    }

    public Long getFromRunId() {
        return fromRunId;
    }

    public Long getToRunId() {
        return toRunId;
    }

    public String getTriggerChangeVersions() {
        return triggerChangeVersions;
    }

    public ChangeSource getSource() {
        return source;
    }

    public boolean isLate() {
        return late;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
