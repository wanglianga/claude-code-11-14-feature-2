package com.example.flightops.service;

import com.example.flightops.config.AppProperties;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.ScheduleVersion;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.ScheduleVersionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 放行判定：绑定业务发生时刻生效的计划时刻版本。
 * 航司事后改计划只产生新版本，不得反向改写已发布结论。
 */
@Service
public class ReleaseJudge {

    private final ScheduleVersionRepository scheduleRepository;
    private final AppProperties properties;

    public ReleaseJudge(ScheduleVersionRepository scheduleRepository, AppProperties properties) {
        this.scheduleRepository = scheduleRepository;
        this.properties = properties;
    }

    public record Judgment(Long scheduleVersionId, boolean normal, int delayMinutes) {
    }

    /** 按首次实际起飞时刻判定：实际起飞 <= 当时生效计划起飞 + 容差 记为正常。 */
    public Judgment judge(FlightInstanceId instanceId, Instant departureTime) {
        ScheduleVersion effective = scheduleRepository.findEffectiveAt(
                        instanceId.getFlightNo(), instanceId.getServiceDate(),
                        instanceId.getLegNo(), departureTime,
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "实例 " + instanceId + " 在 " + departureTime + " 无生效的计划时刻版本，无法判定"));
        long rawDelay = Duration.between(effective.getSchedDeparture(), departureTime).toMinutes();
        boolean normal = rawDelay <= properties.releaseToleranceMinutesOrDefault();
        return new Judgment(effective.getId(), normal, (int) Math.max(0, rawDelay));
    }
}
