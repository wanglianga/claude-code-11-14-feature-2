package com.example.flightops.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 变更日志主键：实例主键 + 实例内变更版本。 */
@Embeddable
public class ChangeLogId implements Serializable {

    private String flightNo;
    private LocalDate serviceDate;
    private Integer legNo;
    private Integer changeVersion;

    public ChangeLogId() {
    }

    public ChangeLogId(String flightNo, LocalDate serviceDate, Integer legNo, Integer changeVersion) {
        this.flightNo = flightNo;
        this.serviceDate = serviceDate;
        this.legNo = legNo;
        this.changeVersion = changeVersion;
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

    public Integer getChangeVersion() {
        return changeVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeLogId that)) return false;
        return Objects.equals(flightNo, that.flightNo)
                && Objects.equals(serviceDate, that.serviceDate)
                && Objects.equals(legNo, that.legNo)
                && Objects.equals(changeVersion, that.changeVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flightNo, serviceDate, legNo, changeVersion);
    }
}
