package com.example.flightops.web.dto;

import com.example.flightops.domain.InstanceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

/** 登记航班实例（含首版计划时刻）。 */
public record RegisterInstanceRequest(
        @NotBlank String flightNo,
        @NotNull LocalDate serviceDate,
        Integer legNo,
        @NotBlank String origin,
        @NotBlank String destination,
        InstanceKind kind,
        @NotNull Instant schedDeparture,
        @NotNull Instant schedArrival,
        Instant effectiveFrom) {

    public int legNoOrDefault() {
        return legNo == null ? 1 : legNo;
    }

    public InstanceKind kindOrDefault() {
        return kind == null ? InstanceKind.REGULAR : kind;
    }
}
