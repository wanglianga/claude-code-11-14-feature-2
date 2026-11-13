package com.example.flightops.domain;

/** 变更日志事件类型。 */
public enum ChangeEventType {
    REGISTER,         // 实例登记（初始 PLANNED 事实）
    STATUS_CHANGE,    // 状态流转
    TIME_CORRECTION,  // 时间更正（如凌晨补录落地/起飞时间）
    SCHEDULE_CHANGE   // 计划时刻变更
}
