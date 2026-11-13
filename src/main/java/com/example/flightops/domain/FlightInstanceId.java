package com.example.flightops.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 航班实例主键：航班号 + 执行日期 + 航段号。 */
@Embeddable
public class FlightInstanceId implements Serializable {

    private String flightNo;
    private LocalDate serviceDate;
    private Integer legNo;

    public FlightInstanceId() {
    }

    public FlightInstanceId(String flightNo, LocalDate serviceDate, Integer legNo) {
        this.flightNo = flightNo;
        this.serviceDate = serviceDate;
        this.legNo = legNo;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public void setFlightNo(String flightNo) {
        this.flightNo = flightNo;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public void setServiceDate(LocalDate serviceDate) {
        this.serviceDate = serviceDate;
    }

    public Integer getLegNo() {
        return legNo;
    }

    public void setLegNo(Integer legNo) {
        this.legNo = legNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FlightInstanceId that)) return false;
        return Objects.equals(flightNo, that.flightNo)
                && Objects.equals(serviceDate, that.serviceDate)
                && Objects.equals(legNo, that.legNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flightNo, serviceDate, legNo);
    }

    @Override
    public String toString() {
        return flightNo + "/" + serviceDate + "/L" + legNo;
    }
}
