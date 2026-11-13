package com.example.flightops.domain;

/** 物化结果运行状态。 */
public enum CaliberRunStatus {
    COMPUTED,    // 已计算，待发布
    PUBLISHED,   // 已发布（该口径该日期当前有效结果）
    SUPERSEDED,  // 被更新的运行取代（迟到事件补偿后旧运行）
    BLOCKED      // 发布被拦截（存在不可解释差额）
}
