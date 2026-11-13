package com.example.flightops.service;

import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.DailyReport;
import com.example.flightops.domain.DailyReportItem;
import com.example.flightops.domain.DailyReportItemId;
import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.ReportCorrection;
import com.example.flightops.domain.ReportCorrectionId;
import com.example.flightops.domain.ReportDiff;
import com.example.flightops.domain.ReportDiffId;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.DailyReportItemRepository;
import com.example.flightops.repo.DailyReportRepository;
import com.example.flightops.repo.FlightInstanceRepository;
import com.example.flightops.repo.ReportCorrectionRepository;
import com.example.flightops.repo.ReportDiffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 日报：每天用同一批事件（变更日志全量重放）重算；逐航班比对放行状态与延误分钟，
 * 差异超阈值定位到具体那次变更；锁定后更正另出一份，原值/更正值/原因同时可查。
 */
@Service
public class ReportService {

    private final DailyReportRepository reportRepository;
    private final DailyReportItemRepository itemRepository;
    private final ReportDiffRepository diffRepository;
    private final ReportCorrectionRepository correctionRepository;
    private final FlightInstanceRepository instanceRepository;
    private final ChangeLogRepository changeLogRepository;
    private final ReplayService replayService;
    private final ReleaseJudge releaseJudge;
    private final com.example.flightops.config.AppProperties properties;

    public ReportService(DailyReportRepository reportRepository,
                         DailyReportItemRepository itemRepository,
                         ReportDiffRepository diffRepository,
                         ReportCorrectionRepository correctionRepository,
                         FlightInstanceRepository instanceRepository,
                         ChangeLogRepository changeLogRepository,
                         ReplayService replayService,
                         ReleaseJudge releaseJudge,
                         com.example.flightops.config.AppProperties properties) {
        this.reportRepository = reportRepository;
        this.itemRepository = itemRepository;
        this.diffRepository = diffRepository;
        this.correctionRepository = correctionRepository;
        this.instanceRepository = instanceRepository;
        this.changeLogRepository = changeLogRepository;
        this.replayService = replayService;
        this.releaseJudge = releaseJudge;
        this.properties = properties;
    }

    /**
     * 重算日报。已存在锁定版本时，新版本必须为 CORRECTION 并填写原因；
     * 同一批事件重放结果确定，差异只来自两次重算之间到达的迟到/补录事件。
     */
    @Transactional
    public DailyReport recompute(LocalDate date, String kind, String reason) {
        List<DailyReport> existing = reportRepository.findByReportDateOrderByVersionAsc(date);
        boolean anyLocked = existing.stream().anyMatch(r -> r.getStatus() == DailyReport.Status.LOCKED);

        DailyReport.Kind reportKind;
        if (anyLocked) {
            if (kind == null || !kind.equalsIgnoreCase("CORRECTION")) {
                throw ApiException.badRequest("该日期已存在锁定版本，重算必须以 kind=CORRECTION 另出一份并填写 reason");
            }
            if (reason == null || reason.isBlank()) {
                throw ApiException.badRequest("更正版本必须填写更正原因 reason");
            }
            reportKind = DailyReport.Kind.CORRECTION;
        } else {
            reportKind = (kind != null && kind.equalsIgnoreCase("CORRECTION"))
                    ? DailyReport.Kind.CORRECTION : DailyReport.Kind.ORIGINAL;
            if (reportKind == DailyReport.Kind.CORRECTION && (reason == null || reason.isBlank())) {
                throw ApiException.badRequest("更正版本必须填写更正原因 reason");
            }
        }

        int nextVersion = existing.stream().mapToInt(DailyReport::getVersion).max().orElse(0) + 1;
        DailyReport previous = existing.stream()
                .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion())).orElse(null);
        Long basedOn = reportKind == DailyReport.Kind.CORRECTION && previous != null ? previous.getId() : null;

        DailyReport report = reportRepository.save(
                new DailyReport(reportRepository.nextId(), date, nextVersion, reportKind, reason, basedOn));

        Map<String, DailyReportItem> previousItems = previous == null ? Map.of()
                : itemRepository.findByIdReportIdOrderByIdFlightNoAscIdLegNoAsc(previous.getId()).stream()
                        .collect(Collectors.toMap(i -> key(i.getId().getFlightNo(), i.getId().getLegNo()), i -> i));

        List<FlightInstance> instances = instanceRepository.findByIdServiceDateOrderByIdFlightNoAscIdLegNoAsc(date);
        List<DailyReportItem> items = new ArrayList<>();
        List<ReportDiff> diffs = new ArrayList<>();
        List<ReportCorrection> corrections = new ArrayList<>();
        int counted = 0, normal = 0, realtimeCounted = 0, realtimeNormal = 0;

        for (FlightInstance instance : instances) {
            ReplayService.ReplayResult replayed = replayService.replay(instance.getId());
            FlightStatus finalStatus = replayed.finalStatus() != null ? replayed.finalStatus() : FlightStatus.PLANNED;

            // 日报口径（写死）：取消不计入分母；备降续飞算一班按首飞判定；应飞未飞计入且记不正常
            boolean itemCounted;
            Boolean itemNormal;
            Integer itemDelay;
            Long scheduleVersionId = null;
            if (finalStatus == FlightStatus.CANCELLED) {
                itemCounted = false;
                itemNormal = null;
                itemDelay = null;
            } else if (replayed.firstDeparture() != null) {
                ReleaseJudge.Judgment judgment = releaseJudge.judge(instance.getId(), replayed.firstDeparture());
                itemCounted = true;
                itemNormal = judgment.normal();
                itemDelay = judgment.delayMinutes();
                scheduleVersionId = judgment.scheduleVersionId();
            } else {
                itemCounted = true;
                itemNormal = false;
                itemDelay = null;
            }

            // 实时口径（重算当时的实例投影）
            boolean rtCounted = instance.getReleaseNormal() != null;
            Boolean rtNormal = instance.getReleaseNormal();
            Integer rtDelay = instance.getReleaseDelayMinutes();

            DailyReportItem item = new DailyReportItem(
                    new DailyReportItemId(report.getId(), instance.getId().getFlightNo(), date, instance.getId().getLegNo()),
                    finalStatus.name(), itemCounted, itemNormal, itemDelay, scheduleVersionId,
                    rtCounted, rtNormal, rtDelay, replayed.lastChangeVersion());
            items.add(item);

            if (itemCounted) {
                counted++;
                if (Boolean.TRUE.equals(itemNormal)) {
                    normal++;
                }
            }
            if (rtCounted) {
                realtimeCounted++;
                if (Boolean.TRUE.equals(rtNormal)) {
                    realtimeNormal++;
                }
            }

            // 逐航班比对上一版本：放行状态与延误分钟
            if (previous != null) {
                DailyReportItem prev = previousItems.get(key(instance.getId().getFlightNo(), instance.getId().getLegNo()));
                String causedBy = causedByChanges(instance, prev == null ? 0 : prev.getLastChangeVersion(),
                        item.getLastChangeVersion());
                diffs.addAll(compareItems(date, previous.getVersion(), nextVersion, item, prev, causedBy));
            }
        }

        itemRepository.saveAll(items);

        // 更正版：所有字段差异都写更正记录（原值、更正值、原因）
        if (reportKind == DailyReport.Kind.CORRECTION) {
            for (ReportDiff diff : diffs) {
                corrections.add(new ReportCorrection(
                        new ReportCorrectionId(date, report.getId(),
                                diff.getId().getFlightNo(), diff.getId().getLegNo(), diff.getId().getField()),
                        diff.getOldValue(), diff.getNewValue(), reason));
            }
            correctionRepository.saveAll(corrections);
        }
        diffRepository.saveAll(diffs);

        report.fillAggregates(instances.size(), counted, normal, StatsService.rate(normal, counted),
                realtimeCounted, realtimeNormal, StatsService.rate(realtimeNormal, realtimeCounted));
        return reportRepository.save(report);
    }

    /** 锁定最新版本；锁定后该日期只能以 CORRECTION 另出新版。 */
    @Transactional
    public DailyReport lock(LocalDate date) {
        DailyReport latest = reportRepository.findTopByReportDateOrderByVersionDesc(date)
                .orElseThrow(() -> ApiException.notFound("该日期暂无日报: " + date));
        if (latest.getStatus() == DailyReport.Status.LOCKED) {
            throw ApiException.conflict("该日期最新版本 v" + latest.getVersion() + " 已锁定");
        }
        latest.lock();
        return reportRepository.save(latest);
    }

    @Transactional(readOnly = true)
    public List<DailyReport> versions(LocalDate date) {
        return reportRepository.findByReportDateOrderByVersionAsc(date);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> versionDetail(LocalDate date, int version) {
        DailyReport report = reportRepository.findByReportDateAndVersion(date, version)
                .orElseThrow(() -> ApiException.notFound("日报不存在: " + date + " v" + version));
        Map<String, Object> result = new HashMap<>();
        result.put("report", report);
        result.put("items", itemRepository.findByIdReportIdOrderByIdFlightNoAscIdLegNoAsc(report.getId()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<ReportDiff> diffs(LocalDate date, int fromVersion, int toVersion) {
        return diffRepository.findByIdReportDateAndIdFromVersionAndIdToVersion(date, fromVersion, toVersion);
    }

    @Transactional(readOnly = true)
    public List<ReportCorrection> corrections(LocalDate date) {
        return correctionRepository.findByIdReportDateOrderByIdCorrectionReportIdAscIdFlightNoAsc(date);
    }

    /** 逐航班比对：正常性翻转、延误分钟差超阈值、是否计入分母变化。 */
    private List<ReportDiff> compareItems(LocalDate date, int fromVersion, int toVersion,
                                          DailyReportItem current, DailyReportItem prev, String causedBy) {
        List<ReportDiff> diffs = new ArrayList<>();
        String flightNo = current.getId().getFlightNo();
        int legNo = current.getId().getLegNo();
        int threshold = properties.diffThresholdMinutesOrDefault();

        boolean prevCounted = prev != null && prev.isCounted();
        if (prevCounted != current.isCounted()) {
            diffs.add(new ReportDiff(new ReportDiffId(date, fromVersion, toVersion, flightNo, legNo, "counted"),
                    String.valueOf(prevCounted), String.valueOf(current.isCounted()), true, causedBy));
        }
        if (!Objects.equals(prev == null ? null : prev.getNormal(), current.getNormal())) {
            diffs.add(new ReportDiff(new ReportDiffId(date, fromVersion, toVersion, flightNo, legNo, "normal"),
                    prev == null ? null : String.valueOf(prev.getNormal()), String.valueOf(current.getNormal()),
                    true, causedBy));
        }
        Integer prevDelay = prev == null ? null : prev.getDelayMinutes();
        if (!Objects.equals(prevDelay, current.getDelayMinutes())) {
            boolean exceeds = prevDelay == null || current.getDelayMinutes() == null
                    || Math.abs(current.getDelayMinutes() - prevDelay) > threshold;
            diffs.add(new ReportDiff(new ReportDiffId(date, fromVersion, toVersion, flightNo, legNo, "delay_minutes"),
                    prevDelay == null ? null : String.valueOf(prevDelay),
                    current.getDelayMinutes() == null ? null : String.valueOf(current.getDelayMinutes()),
                    exceeds, causedBy));
        }
        return diffs;
    }

    /** 定位到具体那次变更：两版之间该实例发生的变更版本列表。 */
    private String causedByChanges(FlightInstance instance, int fromChangeVersion, int toChangeVersion) {
        if (toChangeVersion <= fromChangeVersion) {
            return "";
        }
        return changeLogRepository.findInVersionRange(instance.getId().getFlightNo(),
                        instance.getId().getServiceDate(), instance.getId().getLegNo(),
                        fromChangeVersion, toChangeVersion)
                .stream().map(e -> String.valueOf(e.getId().getChangeVersion()))
                .collect(Collectors.joining(","));
    }

    private static String key(String flightNo, int legNo) {
        return flightNo + "#" + legNo;
    }
}
