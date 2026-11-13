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
 * 下游绑定台账：大屏、月报、对外报送各引用哪一版。
 * 同时绑定口径定义版本（caliber_version）与具体物化结果（run_id），
 * 已锁定月报按当时绑定版本复现，口径切换不回写既有绑定。
 */
@Entity
@Table(name = "downstream_binding")
public class DownstreamBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DownstreamTarget target;

    /** 报送周期，如 2026-11；大屏实时引用可为 null。 */
    @Column(length = 16)
    private String period;

    @Column(nullable = false, length = 32)
    private String caliberCode;

    @Column(nullable = false)
    private int caliberVersion;

    private Long runId;

    private LocalDate serviceDate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, length = 64)
    private String boundBy = "manager";

    @Column(length = 512)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected DownstreamBinding() {
    }

    public DownstreamBinding(DownstreamTarget target, String period, String caliberCode, int caliberVersion,
                             Long runId, LocalDate serviceDate, String boundBy, String note) {
        this.target = target;
        this.period = period;
        this.caliberCode = caliberCode;
        this.caliberVersion = caliberVersion;
        this.runId = runId;
        this.serviceDate = serviceDate;
        this.boundBy = boundBy;
        this.note = note;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public DownstreamTarget getTarget() {
        return target;
    }

    public String getPeriod() {
        return period;
    }

    public String getCaliberCode() {
        return caliberCode;
    }

    public int getCaliberVersion() {
        return caliberVersion;
    }

    public Long getRunId() {
        return runId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public boolean isActive() {
        return active;
    }

    public String getBoundBy() {
        return boundBy;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
