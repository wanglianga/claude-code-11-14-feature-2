package com.example.flightops.domain;

/** 变更来源：实时 / 补录（超迟到窗口） / 计划系统。 */
public enum ChangeSource {
    REALTIME, BACKFILL, SCHEDULE
}
