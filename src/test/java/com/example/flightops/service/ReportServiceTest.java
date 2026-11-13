package com.example.flightops.service;

import com.example.flightops.AbstractIntegrationTest;
import com.example.flightops.domain.DailyReport;
import com.example.flightops.domain.DailyReportItem;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.InstanceKind;
import com.example.flightops.domain.ReportCorrection;
import com.example.flightops.domain.ReportDiff;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.DailyReportItemRepository;
import com.example.flightops.repo.DailyReportRepository;
import com.example.flightops.web.dto.IngestEventRequest;
import com.example.flightops.web.dto.RegisterInstanceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest extends AbstractIntegrationTest {

    @Autowired
    private ReportService reportService;
    @Autowired
    private InstanceService instanceService;
    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private StatsService statsService;
    @Autowired
    private DailyReportRepository reportRepository;
    @Autowired
    private DailyReportItemRepository itemRepository;

    private final LocalDate day = LocalDate.now();

    /** PostgreSQL timestamptz 存微秒，测试时间统一截断到毫秒保证可比较。 */
    private static Instant t(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    private FlightInstanceId register(String flightNo, int legNo, InstanceKind kind, Instant schedDeparture) {
        instanceService.register(new RegisterInstanceRequest(
                flightNo, day, legNo, "ZBAA", "ZSSS", kind,
                schedDeparture, schedDeparture.plus(2, ChronoUnit.HOURS),
                Instant.now().minus(2, ChronoUnit.DAYS)));
        return new FlightInstanceId(flightNo, day, legNo);
    }

    private void send(FlightInstanceId id, FlightStatus to, Instant eventTime) {
        ingestService.ingest(new IngestEventRequest(
                id.getFlightNo(), id.getServiceDate(), id.getLegNo(), to, eventTime, null, null));
    }

    private void walkToDeparture(FlightInstanceId id, Instant departure) {
        send(id, FlightStatus.BOARDING, departure.minus(40, ChronoUnit.MINUTES));
        send(id, FlightStatus.PUSHBACK, departure.minus(10, ChronoUnit.MINUTES));
        send(id, FlightStatus.DEPARTED, departure);
    }

    /**
     * 构造一天：A 正常 / B 延误 60 不正常 / C 取消 / D 同号第二航段备降续飞正常 / E 加班无事件（应飞未飞）。
     */
    @BeforeEach
    void buildDay() {
        Instant now = t(Instant.now());
        // A：正常（延误 10 分钟）
        FlightInstanceId a = register("CA1001", 1, InstanceKind.REGULAR, now.minus(3, ChronoUnit.HOURS));
        walkToDeparture(a, now.minus(3, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES));
        send(a, FlightStatus.LANDED, now.minus(1, ChronoUnit.HOURS));
        // B：不正常（延误 60 分钟）
        FlightInstanceId b = register("MU2002", 1, InstanceKind.REGULAR, now.minus(5, ChronoUnit.HOURS));
        walkToDeparture(b, now.minus(5, ChronoUnit.HOURS).plus(60, ChronoUnit.MINUTES));
        send(b, FlightStatus.LANDED, now.minus(2, ChronoUnit.HOURS));
        // C：取消（不计入分母）
        FlightInstanceId c = register("CZ3003", 1, InstanceKind.REGULAR, now.minus(2, ChronoUnit.HOURS));
        send(c, FlightStatus.CANCELLED, now.minus(3, ChronoUnit.HOURS));
        // D：同号第二航段，备降后续飞，算一班（首飞延误 5 分钟 → 正常）
        FlightInstanceId d = register("CA1001", 2, InstanceKind.REGULAR, now.minus(4, ChronoUnit.HOURS));
        walkToDeparture(d, now.minus(4, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES));
        send(d, FlightStatus.DIVERTED, now.minus(3, ChronoUnit.HOURS));
        send(d, FlightStatus.DEPARTED, now.minus(2, ChronoUnit.HOURS));
        send(d, FlightStatus.LANDED, now.minus(1, ChronoUnit.HOURS));
        // E：加班，全天无事件（日报口径：应飞未飞，计为不正常）
        register("ZH4004", 1, InstanceKind.EXTRA, now.minus(11, ChronoUnit.HOURS));
    }

    @Test
    void dailyCaliberAndRealtimeCaliber() {
        DailyReport v1 = reportService.recompute(day, null, null);
        // 日报口径：分母 = A,B,D,E = 4（C 取消不计入）；正常 = A,D = 2
        assertThat(v1.getCounted()).isEqualTo(4);
        assertThat(v1.getNormal()).isEqualTo(2);
        assertThat(v1.getRate()).isEqualByComparingTo(new BigDecimal("50.00"));
        // 实时口径：分母 = 已判定 A,B,D = 3；正常 2
        assertThat(v1.getRealtimeCounted()).isEqualTo(3);
        assertThat(v1.getRealtimeNormal()).isEqualTo(2);
        assertThat(v1.getRealtimeRate()).isEqualByComparingTo(new BigDecimal("66.67"));

        List<DailyReportItem> items = itemRepository.findByIdReportIdOrderByIdFlightNoAscIdLegNoAsc(v1.getId());
        assertThat(items).hasSize(5);
        DailyReportItem cancelled = items.stream().filter(i -> i.getId().getFlightNo().equals("CZ3003")).findFirst().orElseThrow();
        assertThat(cancelled.isCounted()).isFalse();
        assertThat(cancelled.getNormal()).isNull();
        DailyReportItem diverted = items.stream()
                .filter(i -> i.getId().getFlightNo().equals("CA1001") && i.getId().getLegNo() == 2).findFirst().orElseThrow();
        assertThat(diverted.isCounted()).isTrue();
        assertThat(diverted.getNormal()).isTrue();
        assertThat(diverted.getDelayMinutes()).isEqualTo(5);
        DailyReportItem missing = items.stream().filter(i -> i.getId().getFlightNo().equals("ZH4004")).findFirst().orElseThrow();
        assertThat(missing.isCounted()).isTrue();
        assertThat(missing.getNormal()).isFalse();
        assertThat(missing.isRealtimeCounted()).isFalse();
    }

    @Test
    void sameEventsRecomputeToSameResult() {
        DailyReport v1 = reportService.recompute(day, null, null);
        DailyReport v2 = reportService.recompute(day, null, null);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getCounted()).isEqualTo(v1.getCounted());
        assertThat(v2.getNormal()).isEqualTo(v1.getNormal());
        assertThat(v2.getRate()).isEqualByComparingTo(v1.getRate());
        // 无新事件时版本间无差异
        assertThat(reportService.diffs(day, 1, 2)).isEmpty();
    }

    @Test
    void lockedReportRequiresCorrectionWithReason() {
        reportService.recompute(day, null, null);
        reportService.lock(day);
        // 锁定后普通重算被拒绝
        assertThatThrownBy(() -> reportService.recompute(day, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CORRECTION");
        // 更正必须填原因
        assertThatThrownBy(() -> reportService.recompute(day, "CORRECTION", " "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void backfillTriggersCorrectionVersionAndDiffLocatesChange() {
        DailyReport v1 = reportService.recompute(day, null, null);
        reportService.lock(day);

        // 超窗补录：E（ZH4004）实际已起飞，比计划（11 小时前）晚 5 分钟 → 正常
        FlightInstanceId e = new FlightInstanceId("ZH4004", day, 1);
        Instant departed = t(Instant.now().minus(11, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES));
        send(e, FlightStatus.BOARDING, departed.minus(40, ChronoUnit.MINUTES));
        send(e, FlightStatus.PUSHBACK, departed.minus(10, ChronoUnit.MINUTES));
        send(e, FlightStatus.DEPARTED, departed);

        DailyReport v2 = reportService.recompute(day, "CORRECTION", "ZH4004 补录起飞时间");
        assertThat(v2.getKind()).isEqualTo(DailyReport.Kind.CORRECTION);
        assertThat(v2.getBasedOnId()).isEqualTo(v1.getId());
        // 更正版：正常数 2 → 3，率 50 → 75
        assertThat(v2.getCounted()).isEqualTo(4);
        assertThat(v2.getNormal()).isEqualTo(3);
        assertThat(v2.getRate()).isEqualByComparingTo(new BigDecimal("75.00"));

        // 原版本永不改写
        DailyReport locked = reportRepository.findByReportDateAndVersion(day, 1).orElseThrow();
        assertThat(locked.getNormal()).isEqualTo(2);
        assertThat(locked.getRate()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(locked.getStatus()).isEqualTo(DailyReport.Status.LOCKED);

        // 差异定位到具体那次变更
        List<ReportDiff> diffs = reportService.diffs(day, 1, 2);
        assertThat(diffs).isNotEmpty();
        ReportDiff normalDiff = diffs.stream()
                .filter(d -> d.getId().getFlightNo().equals("ZH4004") && d.getId().getField().equals("normal"))
                .findFirst().orElseThrow();
        assertThat(normalDiff.getOldValue()).isEqualTo("false");
        assertThat(normalDiff.getNewValue()).isEqualTo("true");
        assertThat(normalDiff.isExceedsThreshold()).isTrue();
        assertThat(normalDiff.getCausedByChanges()).isNotBlank();

        // 更正记录：原值、更正值、原因同时可查
        List<ReportCorrection> corrections = reportService.corrections(day);
        assertThat(corrections).isNotEmpty();
        assertThat(corrections).allMatch(c -> c.getReason().equals("ZH4004 补录起飞时间"));
        assertThat(corrections.stream().map(c -> c.getId().getField())).contains("normal");
    }

    @Test
    void realtimeStatsMatchProjection() {
        Map<String, Object> realtime = statsService.realtime(day);
        assertThat(realtime.get("counted")).isEqualTo(3);
        assertThat(realtime.get("normal")).isEqualTo(2);
        assertThat(realtime.get("cancelledExcluded")).isEqualTo(1);
        assertThat(realtime.get("pendingJudgment")).isEqualTo(1);
    }
}
