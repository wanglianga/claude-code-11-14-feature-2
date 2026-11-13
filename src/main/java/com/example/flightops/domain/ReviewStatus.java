package com.example.flightops.domain;

/** 切换评审状态。 */
public enum ReviewStatus {
    PROPOSED,           // 已发起评审
    APPROVED,           // 评审通过（待生效）
    REJECTED,           // 评审驳回
    CONFLICT_DEFERRED,  // 撞日报锁定，锁定优先，顺延下一发布周期
    APPLIED             // 已生效（主口径已切换）
}
