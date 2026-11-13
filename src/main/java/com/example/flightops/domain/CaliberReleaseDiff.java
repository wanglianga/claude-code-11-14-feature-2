package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 发布前逐航班比对差额：发布候选运行与该口径当前已发布运行逐航班比对，
 * 每条差额必须归因到具体状态变更（caused_by_changes 非空 → EXPLAINED）；
 * 出现 UNEXPLAINED 差额即拦截发布。
 */
@Entity
@Table(name = "caliber_release_diff")
public class CaliberReleaseDiff {

    @Embeddable
    public static class Id implements Serializable {

        private LocalDate serviceDate;
        private Long publishRunId;
        private String flightNo;
        private Integer legNo;
        private String field;

        public Id() {
        }

        public Id(LocalDate serviceDate, Long publishRunId, String flightNo, Integer legNo, String field) {
            this.serviceDate = serviceDate;
            this.publishRunId = publishRunId;
            this.flightNo = flightNo;
            this.legNo = legNo;
            this.field = field;
        }

        public LocalDate getServiceDate() {
            return serviceDate;
        }

        public Long getPublishRunId() {
            return publishRunId;
        }

        public String getFlightNo() {
            return flightNo;
        }

        public Integer getLegNo() {
            return legNo;
        }

        public String getField() {
            return field;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id that)) return false;
            return Objects.equals(serviceDate, that.serviceDate)
                    && Objects.equals(publishRunId, that.publishRunId)
                    && Objects.equals(flightNo, that.flightNo)
                    && Objects.equals(legNo, that.legNo)
                    && Objects.equals(field, that.field);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceDate, publishRunId, flightNo, legNo, field);
        }
    }

    @EmbeddedId
    private Id id;

    @Column(nullable = false, length = 32)
    private String caliberCode;

    private Long baseRunId;

    @Column(length = 64)
    private String oldValue;

    @Column(length = 64)
    private String newValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DiffAttribution attribution;

    @Column(length = 256)
    private String causedByChanges;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CaliberReleaseDiff() {
    }

    public CaliberReleaseDiff(Id id, String caliberCode, Long baseRunId, String oldValue, String newValue,
                              DiffAttribution attribution, String causedByChanges) {
        this.id = id;
        this.caliberCode = caliberCode;
        this.baseRunId = baseRunId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.attribution = attribution;
        this.causedByChanges = causedByChanges;
    }

    public Id getId() {
        return id;
    }

    public String getCaliberCode() {
        return caliberCode;
    }

    public Long getBaseRunId() {
        return baseRunId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public DiffAttribution getAttribution() {
        return attribution;
    }

    public String getCausedByChanges() {
        return causedByChanges;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
