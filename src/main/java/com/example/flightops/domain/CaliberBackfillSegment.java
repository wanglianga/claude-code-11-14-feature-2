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
 * 历史补齐区间标注：按新口径补齐历史时，补齐区间与原始区间分列标注。
 * 记录哪版口径、哪个区间、因何补齐，配合 caliber_result_item.backfill_scope 可逐条查到受影响航班。
 */
@Entity
@Table(name = "caliber_backfill_segment")
public class CaliberBackfillSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String caliberCode;

    @Column(nullable = false)
    private int caliberVersion;

    @Column(nullable = false)
    private LocalDate rangeFrom;

    @Column(nullable = false)
    private LocalDate rangeTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackfillScope segmentType;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Column(nullable = false, length = 64)
    private String createdBy = "manager";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CaliberBackfillSegment() {
    }

    public CaliberBackfillSegment(String caliberCode, int caliberVersion, LocalDate rangeFrom, LocalDate rangeTo,
                                  BackfillScope segmentType, String reason, String createdBy) {
        this.caliberCode = caliberCode;
        this.caliberVersion = caliberVersion;
        this.rangeFrom = rangeFrom;
        this.rangeTo = rangeTo;
        this.segmentType = segmentType;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getCaliberCode() {
        return caliberCode;
    }

    public int getCaliberVersion() {
        return caliberVersion;
    }

    public LocalDate getRangeFrom() {
        return rangeFrom;
    }

    public LocalDate getRangeTo() {
        return rangeTo;
    }

    public BackfillScope getSegmentType() {
        return segmentType;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
