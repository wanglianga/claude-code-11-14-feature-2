package com.example.flightops.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** 航司计划时刻变更（产生新计划版本，不回写已发布结论）。 */
public record ScheduleChangeRequest(
        @NotNull Instant schedDeparture,
        @NotNull Instant schedArrival,
        Instant effectiveFrom,
        String source) {
}
