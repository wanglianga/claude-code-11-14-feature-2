package com.example.flightops.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 补偿留痕主键（分区表复合主键）。 */
public class CaliberCompensationId implements Serializable {

    private Long id;
    private LocalDate serviceDate;

    public CaliberCompensationId() {
    }

    public CaliberCompensationId(Long id, LocalDate serviceDate) {
        this.id = id;
        this.serviceDate = serviceDate;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CaliberCompensationId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(serviceDate, that.serviceDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, serviceDate);
    }
}
