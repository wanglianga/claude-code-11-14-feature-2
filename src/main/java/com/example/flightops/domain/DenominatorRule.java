package com.example.flightops.domain;

/**
 * 分母规则（三套口径的第三套差异）：
 * FLOWN_ONLY 仅已起飞班次；FLOWN_PLUS_MISSING 已起飞 + 应飞未飞；ALL_REGISTERED 全部登记实例。
 */
public enum DenominatorRule {
    FLOWN_ONLY,          // 航司：分母仅含已起飞班次
    FLOWN_PLUS_MISSING,  // 机场：已起飞/推出 + 应飞未飞（记不正常）
    ALL_REGISTERED       // 集团：全部登记实例（取消、未飞均计入且记不正常）
}
