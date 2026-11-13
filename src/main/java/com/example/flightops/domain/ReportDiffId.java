package com.example.flightops.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 版本间差异主键：报表日期 + 起止版本 + 航班实例 + 字段。 */
@Embeddable
public class ReportDiffId implements Serializable {

    private LocalDate reportDate;
    private Integer fromVersion;
    private Integer toVersion;
    private String flightNo;
    private Integer legNo;
    private String field;

    public ReportDiffId() {
    }

    public ReportDiffId(LocalDate reportDate, Integer fromVersion, Integer toVersion,
                        String flightNo, Integer legNo, String field) {
        this.reportDate = reportDate;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.flightNo = flightNo;
        this.legNo = legNo;
        this.field = field;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public Integer getFromVersion() {
        return fromVersion;
    }

    public Integer getToVersion() {
        return toVersion;
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
        if (!(o instanceof ReportDiffId that)) return false;
        return Objects.equals(reportDate, that.reportDate)
                && Objects.equals(fromVersion, that.fromVersion)
                && Objects.equals(toVersion, that.toVersion)
                && Objects.equals(flightNo, that.flightNo)
                && Objects.equals(legNo, that.legNo)
                && Objects.equals(field, that.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportDate, fromVersion, toVersion, flightNo, legNo, field);
    }
}
