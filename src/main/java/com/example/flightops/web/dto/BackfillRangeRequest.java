package com.example.flightops.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 按新口径对历史区间补齐：补齐区间与原始区间分列标注。 */
public record BackfillRangeRequest(
        String caliberCode,                  // 空 = 全部生效口径各补一份
        @NotNull LocalDate rangeFrom,
        @NotNull LocalDate rangeTo,
        @NotBlank String reason) {
}
