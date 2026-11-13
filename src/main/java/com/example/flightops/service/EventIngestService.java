package com.example.flightops.service;

import com.example.flightops.config.AppProperties;
import com.example.flightops.domain.Alert;
import com.example.flightops.domain.ChangeEventType;
import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.ChangeLogId;
import com.example.flightops.domain.ChangeSource;
import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.FlightInstanceRepository;
import com.example.flightops.web.dto.IngestEventRequest;
import com.example.flightops.web.dto.IngestResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件摄入：状态机校验 → 迟到判定 → 带正负号与版本写入变更日志（不覆盖旧值）→ 更新实例投影。
 * 窗口内事件触发结果更新与撤回（冲抵）；超窗事件走补录并标注来源。
 */
@Service
public class EventIngestService {

    private final FlightInstanceRepository instanceRepository;
    private final ChangeLogRepository changeLogRepository;
    private final AlertService alertService;
    private final StateMachine stateMachine;
    private final ReleaseJudge releaseJudge;
    private final ReplayService replayService;
    private final AppProperties properties;
    private final CaliberComputeEngine caliberComputeEngine;

    public EventIngestService(FlightInstanceRepository instanceRepository,
                              ChangeLogRepository changeLogRepository,
                              AlertService alertService,
                              StateMachine stateMachine,
                              ReleaseJudge releaseJudge,
                              ReplayService replayService,
                              AppProperties properties,
                              CaliberComputeEngine caliberComputeEngine) {
        this.instanceRepository = instanceRepository;
        this.changeLogRepository = changeLogRepository;
        this.alertService = alertService;
        this.stateMachine = stateMachine;
        this.releaseJudge = releaseJudge;
        this.replayService = replayService;
        this.properties = properties;
        this.caliberComputeEngine = caliberComputeEngine;
    }

    @Transactional
    public IngestResult ingest(IngestEventRequest request) {
        FlightInstanceId id = new FlightInstanceId(request.flightNo(), request.serviceDate(), request.legNoOrDefault());
        FlightInstance instance = instanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("航班实例不存在: " + id + "，请先登记实例"));

        FlightStatus current = instance.getCurrentStatus();
        FlightStatus to = request.toStatus();
        Instant eventTime = request.eventTime();
        Instant now = Instant.now();

        // 迟到窗口：超窗事件标记迟到并强制走补录来源
        boolean late = eventTime.isBefore(now.minus(properties.lateWindowHoursOrDefault(), ChronoUnit.HOURS));
        ChangeSource source = late ? ChangeSource.BACKFILL
                : (request.source() != null ? request.source() : ChangeSource.REALTIME);

        ChangeEventType eventType = resolveEventType(id, current, to);
        if (eventType == null) {
            // 非法流转：拒绝并告警（错误状态比缺状态更难查，必须显式暴露；告警独立事务不被回滚）
            Alert alert = alertService.raise(id, "ILLEGAL_TRANSITION",
                    "非法状态流转被拒绝: " + current + " -> " + to + "，合法目标: " + stateMachine.legalTargets(current));
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "非法状态流转: " + current + " -> " + to + "（未推出不得直接落地等非法路径），已记录告警",
                    Map.of("alertId", alert.getId(), "rule", "ILLEGAL_TRANSITION"));
        }

        int version = instance.getCurrentVersion();
        List<ChangeLogEntry> entries = new ArrayList<>();
        String note = request.note();

        if (eventType == ChangeEventType.STATUS_CHANGE) {
            // -1 冲抵旧状态事实（指针冲抵），+1 计入新状态事实
            Instant oldFactTime = changeLogRepository
                    .findLastPositiveForStatus(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), current,
                            org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst()
                    .map(ChangeLogEntry::getEventTime).orElse(null);
            entries.add(new ChangeLogEntry(new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), ++version),
                    ChangeEventType.STATUS_CHANGE, current, current, (short) -1, oldFactTime, source, late, note));
            entries.add(new ChangeLogEntry(new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), ++version),
                    ChangeEventType.STATUS_CHANGE, current, to, (short) 1, eventTime, source, late, note));
            instance.setCurrentStatus(to);
        } else {
            // TIME_CORRECTION：撤销旧时间事实，计入新时间事实，当前状态不变
            ChangeLogEntry oldFact = changeLogRepository
                    .findLastPositiveForStatus(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), to,
                            org.springframework.data.domain.PageRequest.of(0, 1))
                    .stream().findFirst()
                    .orElseThrow(() -> ApiException.conflict("实例 " + id + " 无可更正的 " + to + " 事实"));
            entries.add(new ChangeLogEntry(new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), ++version),
                    ChangeEventType.TIME_CORRECTION, to, to, (short) -1, oldFact.getEventTime(), source, late,
                    note != null ? note : "时间更正冲抵"));
            entries.add(new ChangeLogEntry(new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), ++version),
                    ChangeEventType.TIME_CORRECTION, to, to, (short) 1, eventTime, source, late,
                    note != null ? note : "时间更正补录"));
        }
        changeLogRepository.saveAll(entries);
        instance.setCurrentVersion(version);

        // 更新实例投影
        if (eventType == ChangeEventType.STATUS_CHANGE) {
            if (to == FlightStatus.DEPARTED && instance.getFirstDepartureTime() == null) {
                instance.setFirstDepartureTime(eventTime);
                applyJudgment(instance, eventTime);
            }
            if (to == FlightStatus.LANDED) {
                instance.setActualArrivalTime(eventTime);
            }
        } else {
            // 时间更正后重放重算事实时间，并按更正后的起飞时刻重新判定（绑定更正时刻生效的计划版本）
            changeLogRepository.flush();
            ReplayService.ReplayResult replayed = replayService.replay(id);
            instance.setFirstDepartureTime(replayed.firstDeparture());
            instance.setActualArrivalTime(replayed.arrival());
            if (replayed.firstDeparture() != null) {
                applyJudgment(instance, replayed.firstDeparture());
            }
        }
        instance.setUpdatedAt(now);
        instanceRepository.save(instance);

        // 多口径并行补偿：窗口内更新与超窗补录都必须向所有并行口径逐口径留痕，禁止只补主口径
        caliberComputeEngine.compensate(id,
                entries.stream().map(e -> e.getId().getChangeVersion()).toList(),
                late, source, note);

        return new IngestResult(id.toString(), eventType.name(),
                entries.stream().map(e -> e.getId().getChangeVersion()).toList(),
                late, source, instance.getCurrentStatus(),
                instance.getReleaseNormal(), instance.getReleaseDelayMinutes(), instance.getReleaseVersionId());
    }

    /**
     * 判定事件类型：
     *  - 目标状态与当前状态相同且可更正（DEPARTED/LANDED）→ 时间更正（如凌晨补录落地时间）；
     *  - 已落地后补报起飞时间 → 起飞时间更正（LANDED→DEPARTED 非法，只能按更正处理）；
     *  - 备降后续飞（DIVERTED→DEPARTED）是合法流转，按状态机处理，不算更正；
     *  - 其余按状态机判定合法流转；不合法返回 null（拒绝并告警）。
     */
    private ChangeEventType resolveEventType(FlightInstanceId id, FlightStatus current, FlightStatus to) {
        if (to == current && stateMachine.isTimeCorrectable(to)) {
            return ChangeEventType.TIME_CORRECTION;
        }
        if (to == FlightStatus.DEPARTED && current == FlightStatus.LANDED
                && !changeLogRepository.findLastPositiveForStatus(
                        id.getFlightNo(), id.getServiceDate(), id.getLegNo(), FlightStatus.DEPARTED,
                        org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty()) {
            return ChangeEventType.TIME_CORRECTION;
        }
        if (stateMachine.isLegal(current, to)) {
            return ChangeEventType.STATUS_CHANGE;
        }
        return null;
    }

    private void applyJudgment(FlightInstance instance, Instant departureTime) {
        ReleaseJudge.Judgment judgment = releaseJudge.judge(instance.getId(), departureTime);
        instance.setReleaseVersionId(judgment.scheduleVersionId());
        instance.setReleaseNormal(judgment.normal());
        instance.setReleaseDelayMinutes(judgment.delayMinutes());
        instance.setReleaseJudgedAt(Instant.now());
    }
}
