package com.example.flightops.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 口径结果明细主键：运行 + 航班实例。 */
@Embeddable
public class CaliberResultItemId implements Serializable {

    private Long runId;
    private LocalDate serviceDate;
    private String flightNo;
    private Integer legNo;

    public CaliberResultItemId() {
    }

    public CaliberResultItemId(Long runId, LocalDate serviceDate, String flightNo, Integer legNo) {
        this.runId = runId;
        this.serviceDate = serviceDate;
        this.flightNo = flightNo;
        this.legNo = legNo;
    }

    public Long getRunId() {
        return runId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public Integer getLegNo() {
        return legNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CaliberResultItemId that)) return false;
        return Objects.equals(runId, that.runId)
                && Objects.equals(serviceDate, that.serviceDate)
                && Objects.equals(flightNo, that.flightNo)
                && Objects.equals(legNo, that.legNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, serviceDate, flightNo, legNo);
    }
}
