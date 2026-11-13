package com.example.flightops.web.dto;

import com.example.flightops.domain.DownstreamTarget;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 下游绑定台账登记：大屏 / 月报 / 对外报送各引用哪一版。
 * 口径版本与物化结果双绑定；runId 为空时只绑定口径定义版本（大屏实时引用场景）。
 */
public record BindingRequest(
        @NotNull DownstreamTarget target,
        String period,                      // 如 2026-11；大屏可空
        @NotNull String caliberCode,
        Integer caliberVersion,             // 空 = 该口径最新生效版本
        Long runId,
        LocalDate serviceDate,
        String note) {
}
