package com.example.flightops.domain;

/** 口径定义版本状态：草稿 / 生效 / 退役。改口径生成新版本，旧版 RETIRED 保留用于历史复现。 */
public enum CaliberStatus {
    DRAFT, ACTIVE, RETIRED
}
