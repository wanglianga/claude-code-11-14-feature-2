package com.example.flightops.web.dto;

import com.example.flightops.domain.DenominatorRule;
import com.example.flightops.domain.TimestampSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 口径定义改版：事后改口径只生成新版本（同 code 下 version 递增），旧版 RETIRED 保留，
 * 已锁定月报按当时绑定版本复现，不追溯改写。changeSummary 必填，说明本版改了什么。
 */
public record NewCaliberVersionRequest(
        @NotNull TimestampSource timestampSource,
        @NotNull DenominatorRule denominatorRule,
        Integer toleranceMinutes,
        @NotBlank @Size(max = 2048) String description,
        @NotBlank @Size(max = 1024) String changeSummary,
        LocalDate effectiveFrom,
        String name) {
}
