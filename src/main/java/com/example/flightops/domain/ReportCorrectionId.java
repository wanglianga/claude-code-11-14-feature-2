package com.example.flightops.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 更正记录主键：报表日期 + 更正版本 + 航班实例 + 字段。 */
@Embeddable
public class ReportCorrectionId implements Serializable {

    private LocalDate reportDate;
    private Long correctionReportId;
    private String flightNo;
    private Integer legNo;
    private String field;

    public ReportCorrectionId() {
    }

    public ReportCorrectionId(LocalDate reportDate, Long correctionReportId,
                              String flightNo, Integer legNo, String field) {
        this.reportDate = reportDate;
        this.correctionReportId = correctionReportId;
        this.flightNo = flightNo;
        this.legNo = legNo;
        this.field = field;
    }

    public LocalDate getReportDate() {
        return reportDate;
    }

    public Long getCorrectionReportId() {
        return correctionReportId;
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
        if (!(o instanceof ReportCorrectionId that)) return false;
        return Objects.equals(reportDate, that.reportDate)
                && Objects.equals(correctionReportId, that.correctionReportId)
                && Objects.equals(flightNo, that.flightNo)
                && Objects.equals(legNo, that.legNo)
                && Objects.equals(field, that.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportDate, correctionReportId, flightNo, legNo, field);
    }
}
