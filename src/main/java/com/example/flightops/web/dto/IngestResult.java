package com.example.flightops.web.dto;

import com.example.flightops.domain.ChangeSource;
import com.example.flightops.domain.FlightStatus;

import java.util.List;

/** 事件摄入结果。 */
public record IngestResult(
        String instanceKey,
        String eventType,
        List<Integer> changeVersions,
        boolean late,
        ChangeSource source,
        FlightStatus currentStatus,
        Boolean releaseNormal,
        Integer releaseDelayMinutes,
        Long releaseScheduleVersionId) {
}
