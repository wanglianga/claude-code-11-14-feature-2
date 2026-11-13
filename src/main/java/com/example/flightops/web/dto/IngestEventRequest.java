package com.example.flightops.web.dto;

import com.example.flightops.domain.ChangeSource;
import com.example.flightops.domain.FlightStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;

/** 上报一次状态变更事件。 */
public record IngestEventRequest(
        @NotBlank String flightNo,
        @NotNull LocalDate serviceDate,
        Integer legNo,
        @NotNull FlightStatus toStatus,
        @NotNull Instant eventTime,
        ChangeSource source,
        String note) {

    public int legNoOrDefault() {
        return legNo == null ? 1 : legNo;
    }
}
