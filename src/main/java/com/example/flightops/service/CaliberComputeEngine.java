package com.example.flightops.service;

import com.example.flightops.domain.BackfillScope;
import com.example.flightops.domain.CaliberCompensation;
import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberResultItem;
import com.example.flightops.domain.CaliberResultItemId;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.ChangeEventType;
import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.ChangeSource;
import com.example.flightops.domain.CompensationKind;
import com.example.flightops.domain.DenominatorRule;
import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.ScheduleVersion;
import com.example.flightops.domain.TimestampSource;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberCompensationRepository;
import com.example.flightops.repo.CaliberResultItemRepository;
import com.example.flightops.repo.CaliberRunRepository;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.FlightInstanceRepository;
import com.example.flightops.repo.ScheduleVersionRepository;
import com.example.flightops.config.AppProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 并行计算引擎：同一批变更事件（flight_change_log）按每个口径各跑一份带版本的物化结果。
 * 选型取舍：不做"一份明细 + 查询时按口径现算"——口径定义一旦改动，历史报表会随之变化、
 * 当时报送数字无法复现；这里每套口径各存一份物化结果，运行行内快照口径定义并绑定版本号，
 * 口径改版只影响之后的新运行，已锁定月报按绑定版本永久复现。
 */
@Service
public class CaliberComputeEngine {

    private final CaliberRunRepository runRepository;
    private final CaliberResultItemRepository itemRepository;
    private final CaliberCompensationRepository compensationRepository;
    private final CaliberRegistryService registryService;
    private final FlightInstanceRepository instanceRepository;
    private final ChangeLogRepository changeLogRepository;
    private final ScheduleVersionRepository scheduleRepository;
    private final ReplayService replayService;
    private final AppProperties properties;

    public CaliberComputeEngine(CaliberRunRepository runRepository,
                                CaliberResultItemRepository itemRepository,
                                CaliberCompensationRepository compensationRepository,
                                CaliberRegistryService registryService,
                                FlightInstanceRepository instanceRepository,
                                ChangeLogRepository changeLogRepository,
                                ScheduleVersionRepository scheduleRepository,
                                ReplayService replayService,
                                AppProperties properties) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.compensationRepository = compensationRepository;
        this.registryService = registryService;
        this.instanceRepository = instanceRepository;
        this.changeLogRepository = changeLogRepository;
        this.scheduleRepository = scheduleRepository;
        this.replayService = replayService;
        this.properties = properties;
    }

    /** 单航班在某口径下的评估中间结果。 */
    public record EvalResult(boolean counted, Boolean normal, Integer delayMinutes, Instant referenceTime,
                             Long scheduleVersionId, int lastChangeVersion, BackfillScope itemScope,
                             String finalStatus) {
    }

    /**
     * 并行计算：对当天全部生效口径各产出一份物化运行。
     *
     * @param forcedScope ORIGINAL=常规并行计算；BACKFILL=历史补齐区间计算（运行与明细分列标注）
     */
    @Transactional
    public List<CaliberRun> computeParallel(LocalDate date, BackfillScope forcedScope, String reason) {
        List<CaliberDefinition> calibers = registryService.activeCalibers();
        if (calibers.isEmpty()) {
            throw ApiException.conflict("注册库中无生效口径，无法并行计算");
        }
        List<CaliberRun> runs = new ArrayList<>();
        for (CaliberDefinition caliber : calibers) {
            runs.add(computeOne(date, caliber, forcedScope, reason, null));
        }
        return runs;
    }

    /** 单口径单日计算（历史补齐指定口径版本时使用）。 */
    @Transactional
    public CaliberRun computeOne(LocalDate date, CaliberDefinition caliber, BackfillScope forcedScope,
                                 String reason, FlightInstanceId onlyAffected) {
        int runNo = runRepository.findByServiceDateAndCaliberCodeOrderByRunNoAsc(date, caliber.getCode())
                .stream().mapToInt(CaliberRun::getRunNo).max().orElse(0) + 1;
        int tolerance = caliber.getToleranceMinutes() != null
                ? caliber.getToleranceMinutes() : properties.releaseToleranceMinutesOrDefault();

        CaliberRun run = new CaliberRun(runRepository.nextId(), date, caliber.getCode(), caliber.getVersion(),
                runNo, caliber.getTimestampSource(), caliber.getDenominatorRule(), tolerance, forcedScope, reason);
        run = runRepository.save(run);

        List<FlightInstance> instances =
                instanceRepository.findByIdServiceDateOrderByIdFlightNoAscIdLegNoAsc(date);
        List<CaliberResultItem> items = new ArrayList<>();
        int counted = 0, normal = 0;
        boolean anyBackfillItem = forcedScope == BackfillScope.BACKFILL;

        for (FlightInstance instance : instances) {
            FlightInstanceId iid = instance.getId();
            List<ChangeLogEntry> entries = changeLogRepository
                    .findByIdFlightNoAndIdServiceDateAndIdLegNoOrderByIdChangeVersionAsc(
                            iid.getFlightNo(), date, iid.getLegNo());
            ReplayService.ReplayResult replayed = replayService.replay(entries);
            EvalResult eval = evaluate(caliber, tolerance, instance, entries, replayed);

            BackfillScope itemScope = eval.itemScope();
            if (onlyAffected != null && iid.equals(onlyAffected)) {
                itemScope = BackfillScope.BACKFILL;
            }
            if (itemScope == BackfillScope.BACKFILL) {
                anyBackfillItem = true;
            }

            CaliberResultItem item = new CaliberResultItem(
                    new CaliberResultItemId(run.getId(), date, iid.getFlightNo(), iid.getLegNo()),
                    eval.finalStatus(), eval.counted(), eval.normal(), eval.delayMinutes(),
                    eval.referenceTime(), eval.scheduleVersionId(), eval.lastChangeVersion(), itemScope);
            items.add(item);

            if (eval.counted()) {
                counted++;
                if (Boolean.TRUE.equals(eval.normal())) {
                    normal++;
                }
            }
        }
        itemRepository.saveAll(items);
        run.fillAggregates(instances.size(), counted, normal, StatsService.rate(normal, counted));
        if (anyBackfillItem) {
            // 运行级区间标注：含任一补齐明细即标记为补齐区间运行，与原始区间分列
            run.markBackfillScope();
        }
        return runRepository.save(run);
    }

    /**
     * 逐口径补偿：一次实例变更（窗口内更新或超窗补录）必须向所有并行口径同时补偿，
     * 每个口径各留一条补偿记录、各产一份新物化运行。只补主口径会让两套结果在某个分区悄悄分叉。
     */
    @Transactional
    public List<CaliberCompensation> compensate(FlightInstanceId id, List<Integer> triggerVersions,
                                                boolean late, ChangeSource source, String note) {
        // 该日期尚未产出过任何口径结果时无可补偿对象：首次并行计算会直接纳入本事件，无需逐事件物化
        if (runRepository.findByServiceDateOrderByCaliberCodeAscRunNoAsc(id.getServiceDate()).isEmpty()) {
            return List.of();
        }
        List<CaliberDefinition> calibers = registryService.activeCalibers();
        CompensationKind kind = late ? CompensationKind.LATE_BACKFILL : CompensationKind.REALTIME_UPDATE;
        String versionsCsv = triggerVersions.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        List<CaliberCompensation> records = new ArrayList<>();

        for (CaliberDefinition caliber : calibers) {
            CaliberRun previous = runRepository
                    .findTopByServiceDateAndCaliberCodeOrderByRunNoDesc(id.getServiceDate(), caliber.getCode())
                    .orElse(null);
            Long fromRunId = previous == null ? null : previous.getId();

            // 补偿只产出 COMPUTED 候选运行；已发布运行保持 PUBLISHED 作为发布仲裁的比对基线，
            // 仲裁通过后才在 publish() 中置 SUPERSEDED；若发布被拦截，权威结果仍是旧发布运行。
            CaliberRun fresh = computeOne(id.getServiceDate(), caliber,
                    late ? BackfillScope.BACKFILL : BackfillScope.ORIGINAL,
                    (late ? "超窗补录补偿: " : "窗口内更新补偿: ") + id + " 变更版本 " + versionsCsv,
                    id);

            CaliberCompensation compensation = new CaliberCompensation(
                    compensationRepository.nextId(), id.getServiceDate(), caliber.getCode(), caliber.getVersion(),
                    kind, id.getFlightNo(), id.getLegNo(), fromRunId, fresh.getId(),
                    versionsCsv, source, late, note);
            records.add(compensationRepository.save(compensation));
        }
        return records;
    }

    /** 按口径评估单个实例：时间戳来源决定基准事实，分母规则决定是否计入。 */
    public EvalResult evaluate(CaliberDefinition caliber, int tolerance, FlightInstance instance,
                               List<ChangeLogEntry> entries, ReplayService.ReplayResult replayed) {
        FlightInstanceId iid = instance.getId();
        FlightStatus finalStatus = replayed.finalStatus() != null ? replayed.finalStatus() : FlightStatus.PLANNED;
        boolean hasBackfill = entries.stream().anyMatch(e -> e.getSource() == ChangeSource.BACKFILL);
        BackfillScope itemScope = hasBackfill ? BackfillScope.BACKFILL : BackfillScope.ORIGINAL;

        Instant referenceTime = referenceTime(caliber.getTimestampSource(), replayed, entries);
        boolean cancelled = finalStatus == FlightStatus.CANCELLED;

        boolean counted = switch (caliber.getDenominatorRule()) {
            case FLOWN_ONLY -> referenceTime != null;                // 仅已达成口径里程碑的班次
            case FLOWN_PLUS_MISSING -> !cancelled;                   // 取消不计；应飞未飞计入
            case ALL_REGISTERED -> true;                              // 集团：全部登记实例计入
        };
        if (!counted) {
            return new EvalResult(false, null, null, referenceTime, null,
                    replayed.lastChangeVersion(), itemScope, finalStatus.name());
        }
        if (referenceTime == null) {
            // 计入分母但里程碑未达成（应飞未飞/取消但集团口径仍计入）：记不正常
            return new EvalResult(true, false, null, null, null,
                    replayed.lastChangeVersion(), itemScope, finalStatus.name());
        }

        ScheduleVersion effective = scheduleRepository.findEffectiveAt(
                        iid.getFlightNo(), iid.getServiceDate(), iid.getLegNo(), referenceTime,
                        PageRequest.of(0, 1))
                .stream().findFirst()
                .orElseThrow(() -> ApiException.conflict(
                        "实例 " + iid + " 在 " + referenceTime + " 无生效计划版本，口径 " + caliber.getCode() + " 无法判定"));
        Instant scheduled = caliber.getTimestampSource() == TimestampSource.ARRIVAL
                ? effective.getSchedArrival() : effective.getSchedDeparture();
        int delay = (int) Math.max(0, Duration.between(scheduled, referenceTime).toMinutes());
        boolean normal = delay <= tolerance;
        return new EvalResult(true, normal, delay, referenceTime, effective.getId(),
                replayed.lastChangeVersion(), itemScope, finalStatus.name());
    }

    /**
     * 口径时间戳来源对应的事实时间：
     * PUSHBACK=首次实际推出（机场视角，推出与起飞之间的等待不计入机场延误）；
     * DEPARTURE=首次实际起飞（航司视角）；ARRIVAL=实际到达（集团视角）。
     */
    private Instant referenceTime(TimestampSource source, ReplayService.ReplayResult replayed,
                                  List<ChangeLogEntry> entries) {
        return switch (source) {
            case DEPARTURE -> replayed.firstDeparture();
            case ARRIVAL -> replayed.arrival();
            case PUSHBACK -> netFactTimes(entries, FlightStatus.PUSHBACK).stream().min(Instant::compareTo).orElse(null);
        };
    }

    /** 冲抵后仍有效的某状态事实时间集合（TIME_CORRECTION 的 -1 会撤掉旧时间事实）。 */
    private List<Instant> netFactTimes(List<ChangeLogEntry> entries, FlightStatus status) {
        Map<Instant, Integer> net = new HashMap<>();
        for (ChangeLogEntry e : entries) {
            if (e.getToStatus() != status || e.getEventTime() == null) {
                continue;
            }
            if (e.getEventType() == ChangeEventType.TIME_CORRECTION) {
                net.merge(e.getEventTime(), (int) e.getSign(), Integer::sum);
            } else if (e.getSign() > 0
                    && (e.getEventType() == ChangeEventType.REGISTER
                        || e.getEventType() == ChangeEventType.STATUS_CHANGE)) {
                net.merge(e.getEventTime(), 1, Integer::sum);
            }
        }
        return net.entrySet().stream().filter(en -> en.getValue() > 0).map(Map.Entry::getKey).toList();
    }
}
