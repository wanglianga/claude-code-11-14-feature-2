package com.example.flightops.web.dto;

import com.example.flightops.domain.DenominatorRule;
import com.example.flightops.domain.TimestampSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 注册一套新口径（首版）。 */
public record RegisterCaliberRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 128) String name,
        @NotNull TimestampSource timestampSource,
        @NotNull DenominatorRule denominatorRule,
        Integer toleranceMinutes,
        @NotBlank @Size(max = 2048) String description,
        LocalDate effectiveFrom) {
}
