package com.example.flightops.domain;

/**
 * 航班实例状态。状态流转由 {@code StateMachine} 校验：
 * 取消后恢复、起飞后备降、备降后取消为合法路径；未推出直接落地拒绝并告警。
 */
public enum FlightStatus {
    PLANNED,    // 计划
    ESTIMATED,  // 预计（调时）
    DELAYED,    // 延误
    BOARDING,   // 登机
    PUSHBACK,   // 推出
    DEPARTED,   // 起飞
    DIVERTED,   // 备降
    LANDED,     // 落地（终态）
    CANCELLED,  // 取消
    RESTORED    // 取消后恢复
}
