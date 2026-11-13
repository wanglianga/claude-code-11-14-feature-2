package com.example.flightops.service;

import com.example.flightops.domain.ChangeEventType;
import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.repo.ChangeLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 变更日志重放：同一批事件按实例主键逐条冲抵，得到最终状态与事实时间。
 * 语义约定：
 *  - REGISTER / STATUS_CHANGE 的 +1：计入一条状态事实并移动当前状态指针；
 *  - STATUS_CHANGE 的 -1：仅作状态指针冲抵（供下游计数冲抵），不撤销历史时间事实；
 *  - TIME_CORRECTION 的 -1/+1：撤销旧时间事实并计入新时间事实（如凌晨补录落地时间）。
 * 日报口径以此重放结果为准，保证同一批事件重算结果一致。
 */
@Service
public class ReplayService {

    private final ChangeLogRepository changeLogRepository;

    public ReplayService(ChangeLogRepository changeLogRepository) {
        this.changeLogRepository = changeLogRepository;
    }

    /**
     * @param finalStatus       重放后的最终状态
     * @param firstDeparture    首次实际起飞时刻（冲抵后的净事实；时间更正后取更正值）
     * @param arrival           实际落地时刻（冲抵后的净事实）
     * @param lastChangeVersion 该实例最大变更版本（用于版本间差异定位）
     */
    public record ReplayResult(FlightStatus finalStatus, Instant firstDeparture, Instant arrival,
                               int lastChangeVersion) {
    }

    public ReplayResult replay(FlightInstanceId id) {
        List<ChangeLogEntry> entries = changeLogRepository
                .findByIdFlightNoAndIdServiceDateAndIdLegNoOrderByIdChangeVersionAsc(
                        id.getFlightNo(), id.getServiceDate(), id.getLegNo());
        return replay(entries);
    }

    public ReplayResult replay(List<ChangeLogEntry> entries) {
        // 净事实表：(状态, 业务时间) -> 冲抵后的净计数
        Map<FactKey, Integer> netFacts = new HashMap<>();
        FlightStatus finalStatus = null;
        int lastChangeVersion = 0;

        for (ChangeLogEntry e : entries) {
            lastChangeVersion = Math.max(lastChangeVersion, e.getId().getChangeVersion());
            if (e.getToStatus() == null) {
                continue; // SCHEDULE_CHANGE 等审计条目不参与状态重放
            }
            FactKey key = new FactKey(e.getToStatus(), e.getEventTime());
            if (e.getEventType() == ChangeEventType.TIME_CORRECTION) {
                netFacts.merge(key, (int) e.getSign(), Integer::sum);
            } else if (e.getSign() > 0
                    && (e.getEventType() == ChangeEventType.REGISTER
                        || e.getEventType() == ChangeEventType.STATUS_CHANGE)) {
                netFacts.merge(key, 1, Integer::sum);
                finalStatus = e.getToStatus();
            }
        }

        Instant firstDeparture = netFacts.entrySet().stream()
                .filter(en -> en.getKey().status() == FlightStatus.DEPARTED && en.getValue() > 0)
                .map(en -> en.getKey().eventTime())
                .filter(t -> t != null)
                .min(Instant::compareTo)
                .orElse(null);
        Instant arrival = netFacts.entrySet().stream()
                .filter(en -> en.getKey().status() == FlightStatus.LANDED && en.getValue() > 0)
                .map(en -> en.getKey().eventTime())
                .filter(t -> t != null)
                .max(Instant::compareTo)
                .orElse(null);

        return new ReplayResult(finalStatus, firstDeparture, arrival, lastChangeVersion);
    }

    private record FactKey(FlightStatus status, Instant eventTime) {
    }
}
