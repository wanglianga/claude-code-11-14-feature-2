package com.example.flightops.web.dto;

/** 日报重算请求：kind=ORIGINAL/CORRECTION，更正版必须填 reason。 */
public record RecomputeRequest(String kind, String reason) {
}
