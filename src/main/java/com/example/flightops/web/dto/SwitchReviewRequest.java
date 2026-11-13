package com.example.flightops.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 发起主口径切换评审。 */
public record SwitchReviewRequest(
        @NotNull LocalDate serviceDate,
        @NotBlank String toCaliberCode,
        Integer toCaliberVersion,           // 空 = 目标口径最新生效版本
        @NotBlank String reason) {
}
