package com.example.flightops.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 日报明细主键：报表 + 航班实例。 */
@Embeddable
public class DailyReportItemId implements Serializable {

    private Long reportId;
    private String flightNo;
    private LocalDate serviceDate;
    private Integer legNo;

    public DailyReportItemId() {
    }

    public DailyReportItemId(Long reportId, String flightNo, LocalDate serviceDate, Integer legNo) {
        this.reportId = reportId;
        this.flightNo = flightNo;
        this.serviceDate = serviceDate;
        this.legNo = legNo;
    }

    public Long getReportId() {
        return reportId;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public Integer getLegNo() {
        return legNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DailyReportItemId that)) return false;
        return Objects.equals(reportId, that.reportId)
                && Objects.equals(flightNo, that.flightNo)
                && Objects.equals(serviceDate, that.serviceDate)
                && Objects.equals(legNo, that.legNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportId, flightNo, serviceDate, legNo);
    }
}
