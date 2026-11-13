package com.example.flightops.domain;

/** 计算区间：ORIGINAL 原始区间；BACKFILL 历史补齐/超窗补录区间，两类区间分列标注。 */
public enum BackfillScope {
    ORIGINAL, BACKFILL
}
