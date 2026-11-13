package com.example.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运行参数（统计口径的数值部分，口径文字说明见 CaliberService 与 README）。 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Integer releaseToleranceMinutes,
        Integer lateWindowHours,
        Integer diffThresholdMinutes,
        Security security) {

    public record Security(String managerPassword, String viewerPassword) {
    }

    public int releaseToleranceMinutesOrDefault() {
        return releaseToleranceMinutes == null ? 20 : releaseToleranceMinutes;
    }

    public int lateWindowHoursOrDefault() {
        return lateWindowHours == null ? 6 : lateWindowHours;
    }

    public int diffThresholdMinutesOrDefault() {
        return diffThresholdMinutes == null ? 5 : diffThresholdMinutes;
    }
}
