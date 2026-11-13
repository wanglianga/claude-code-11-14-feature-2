package com.example.flightops.domain;

/** 补偿类型：窗口内更新触发的并行补偿 / 超窗补录触发的并行补偿。 */
public enum CompensationKind {
    REALTIME_UPDATE, LATE_BACKFILL
}
