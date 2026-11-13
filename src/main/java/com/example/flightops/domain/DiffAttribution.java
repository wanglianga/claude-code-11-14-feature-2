package com.example.flightops.domain;

/** 发布差额归因：EXPLAINED 可归因到具体状态变更；UNEXPLAINED 无法解释，拦截发布。 */
public enum DiffAttribution {
    EXPLAINED, UNEXPLAINED
}
