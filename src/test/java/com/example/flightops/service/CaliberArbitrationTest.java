package com.example.flightops.service;

import com.example.flightops.AbstractIntegrationTest;
import com.example.flightops.domain.BackfillScope;
import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.CaliberRunStatus;
import com.example.flightops.domain.CaliberStatus;
import com.example.flightops.domain.CaliberSwitchConflict;
import com.example.flightops.domain.CaliberSwitchReview;
import com.example.flightops.domain.DenominatorRule;
import com.example.flightops.domain.DiffAttribution;
import com.example.flightops.domain.DownstreamBinding;
import com.example.flightops.domain.DownstreamTarget;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.InstanceKind;
import com.example.flightops.domain.ReviewStatus;
import com.example.flightops.domain.TimestampSource;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberReleaseDiffRepository;
import com.example.flightops.repo.CaliberRunRepository;
import com.example.flightops.web.dto.BindingRequest;
import com.example.flightops.web.dto.IngestEventRequest;
import com.example.flightops.web.dto.NewCaliberVersionRequest;
import com.example.flightops.web.dto.RegisterInstanceRequest;
import com.example.flightops.web.dto.ReviewDecisionRequest;
import com.example.flightops.web.dto.SwitchReviewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发布仲裁与治理故事线：
 * 差额归因/不可解释拦截、切换评审撞日报锁定顺延留痕、口径改版双版本绑定与历史复现、
 * 下游绑定台账、历史补齐区间分列。
 */
class CaliberArbitrationTest extends AbstractIntegrationTest {

    @Autowired private CaliberComputeEngine computeEngine;
    @Autowired private CaliberReleaseArbitrationService arbitrationService;
    @Autowired private CaliberRegistryService registryService;
    @Autowired private DownstreamBindingService bindingService;
    @Autowired private CaliberBackfillService backfillService;
    @Autowired private ReportService reportService;
    @Autowired private InstanceService instanceService;
    @Autowired private EventIngestService ingestService;
    @Autowired private CaliberRunRepository runRepository;
    @Autowired private CaliberReleaseDiffRepository diffRepository;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate day = LocalDate.now();

    private static Instant t(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    private FlightInstanceId register(String flightNo, Instant schedDeparture) {
        instanceService.register(new RegisterInstanceRequest(
                flightNo, day, 1, "ZBAA", "ZSSS", InstanceKind.REGULAR,
                schedDeparture, schedDeparture.plus(2, ChronoUnit.HOURS),
                Instant.now().minus(2, ChronoUnit.DAYS)));
        return new FlightInstanceId(flightNo, day, 1);
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

    @BeforeEach
    void buildDay() {
        Instant now = t(Instant.now());
        // 起飞延误 20 分钟：航司 v1 容差 15 → 不正常；改版容差 30 后 → 正常（制造可归因的口径版本差额）
        FlightInstanceId a = register("CA8001", now.minus(4, ChronoUnit.HOURS));
        walkToDeparture(a, now.minus(4, ChronoUnit.HOURS).plus(20, ChronoUnit.MINUTES));
        send(a, FlightStatus.LANDED, now.minus(2, ChronoUnit.HOURS));
        register("MU8002", now.minus(3, ChronoUnit.HOURS)); // 应飞未飞
    }

    @Test
    void publishFirstRunThenExplainedDiffOnRecompute() {
        List<CaliberRun> v1Runs = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "首次并行计算");
        CaliberRun airlineV1 = v1Runs.stream().filter(r -> r.getCaliberCode().equals("AIRLINE")).findFirst().orElseThrow();
        // 首次发布无基线，直接发布
        var first = arbitrationService.publish(day, airlineV1.getId());
        assertThat(first.blocked()).isFalse();
        assertThat(first.diffs()).isEmpty();
        assertThat(runRepository.findById(new com.example.flightops.domain.CaliberRunId(airlineV1.getId(), day))
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.PUBLISHED);

        // 新事件：MU8002 补飞起飞（窗口内），触发并行补偿产出候选运行
        FlightInstanceId mu = new FlightInstanceId("MU8002", day, 1);
        Instant departed = t(Instant.now().minus(2, ChronoUnit.HOURS).plus(8, ChronoUnit.MINUTES));
        walkToDeparture(mu, departed);

        CaliberRun airlineLatest = runRepository
                .findTopByServiceDateAndCaliberCodeOrderByRunNoDesc(day, "AIRLINE").orElseThrow();
        assertThat(airlineLatest.getStatus()).isEqualTo(CaliberRunStatus.COMPUTED);
        var outcome = arbitrationService.publish(day, airlineLatest.getId());

        // 差额全部可归因到具体变更版本，发布通过，旧基线被取代
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.diffs()).isNotEmpty();
        assertThat(outcome.diffs()).allMatch(d -> d.getAttribution() == DiffAttribution.EXPLAINED);
        assertThat(runRepository.findById(new com.example.flightops.domain.CaliberRunId(airlineV1.getId(), day))
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.SUPERSEDED);
        assertThat(runRepository.findById(new com.example.flightops.domain.CaliberRunId(airlineLatest.getId(), day))
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.PUBLISHED);
    }

    @Test
    void unexplainedDifferenceBlocksPublish() {
        List<CaliberRun> v1Runs = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "首次并行计算");
        CaliberRun airlineV1 = v1Runs.stream().filter(r -> r.getCaliberCode().equals("AIRLINE")).findFirst().orElseThrow();
        arbitrationService.publish(day, airlineV1.getId());

        // 无新事件再算一版（结果本应完全一致）
        CaliberRun airlineV2 = computeEngine.computeOne(day, registryService.latestActive("AIRLINE"),
                BackfillScope.ORIGINAL, "复算", null);
        // 人为制造无变更支撑的差额：篡改明细结论但抹平变更版本号 → 不可解释
        // （CA8001 在 v1 容差 15 下本为不正常，篡改为正常且无变更版本可归因）
        jdbc.update("UPDATE caliber_result_item SET normal = TRUE, last_change_version = 0 "
                        + "WHERE run_id = ? AND flight_no = 'CA8001'",
                airlineV2.getId());

        assertThatThrownBy(() -> arbitrationService.publish(day, airlineV2.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("拦截");
        assertThat(runRepository.findById(new com.example.flightops.domain.CaliberRunId(airlineV2.getId(), day))
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.BLOCKED);
        // 拦截原因落库可查
        assertThat(diffRepository.findByIdServiceDateAndIdPublishRunIdAndAttribution(
                day, airlineV2.getId(), DiffAttribution.UNEXPLAINED)).isNotEmpty();
        // 旧发布结果仍是权威
        assertThat(runRepository.findById(new com.example.flightops.domain.CaliberRunId(airlineV1.getId(), day))
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.PUBLISHED);
    }

    @Test
    void switchReviewAppliesWhenNoLockAndDefersWhenDailyReportLocked() {
        // 无锁定：机场 → 航司 切换评审通过即生效
        CaliberSwitchReview review = arbitrationService.proposeSwitch(
                new SwitchReviewRequest(day, "AIRLINE", null, "航司口径作为对外主口径"), "manager");
        arbitrationService.decide(review.getId(), new ReviewDecisionRequest(true, "同意切换"), "manager");
        CaliberSwitchReview applied = arbitrationService.currentMainReview();
        assertThat(applied.getToCaliberCode()).isEqualTo("AIRLINE");
        assertThat(applied.getStatus()).isEqualTo(ReviewStatus.APPLIED);

        // 锁定当日日报后再发起 航司 → 集团 切换：锁定优先，顺延并留冲突记录
        reportService.recompute(day, null, null);
        reportService.lock(day);
        CaliberSwitchReview blocked = arbitrationService.proposeSwitch(
                new SwitchReviewRequest(day, "GROUP", null, "集团报送口径上线"), "manager");
        CaliberSwitchReview deferred = arbitrationService.decide(
                blocked.getId(), new ReviewDecisionRequest(true, "同意但遇锁定"), "manager");
        assertThat(deferred.getStatus()).isEqualTo(ReviewStatus.CONFLICT_DEFERRED);
        assertThat(deferred.getEffectiveDate()).isEqualTo(day.plusDays(1));

        List<CaliberSwitchConflict> conflicts = arbitrationService.conflicts(day);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getDeferredToDate()).isEqualTo(day.plusDays(1));
        assertThat(conflicts.get(0).isResolved()).isFalse();
        // 主口径仍是航司，未被切换
        assertThat(arbitrationService.currentMainReview().getToCaliberCode()).isEqualTo("AIRLINE");

        // 顺延周期（次日）无锁定 → 落地切换，冲突记录标记解决
        CaliberSwitchReview resolved = arbitrationService.resolveDeferred(blocked.getId(), "manager");
        assertThat(resolved.getStatus()).isEqualTo(ReviewStatus.APPLIED);
        assertThat(resolved.getEffectiveDate()).isEqualTo(day.plusDays(1));
        assertThat(arbitrationService.conflicts(day).get(0).isResolved()).isTrue();
        assertThat(arbitrationService.currentMainReview().getToCaliberCode()).isEqualTo("GROUP");
    }

    @Test
    void rejectedReviewDoesNotChangeMainCaliber() {
        CaliberSwitchReview review = arbitrationService.proposeSwitch(
                new SwitchReviewRequest(day, "GROUP", null, "试探性切换"), "manager");
        arbitrationService.decide(review.getId(), new ReviewDecisionRequest(false, "口径未评审完"), "manager");
        CaliberSwitchReview reloaded = arbitrationService.reviews(day).stream()
                .filter(r -> r.getId().equals(review.getId())).findFirst().orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(arbitrationService.currentMainReview().getToCaliberCode()).isEqualTo("AIRPORT");
    }

    @Test
    void caliberChangeCreatesNewVersionAndHistoricalRunStaysReproducible() {
        List<CaliberRun> v1Runs = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "首次并行计算");
        CaliberRun airlineV1 = v1Runs.stream().filter(r -> r.getCaliberCode().equals("AIRLINE")).findFirst().orElseThrow();
        arbitrationService.publish(day, airlineV1.getId());
        int countedBefore = airlineV1.getCounted();

        // 口径改版：航司容差 15 → 30 分钟，生成 v2，旧版退役
        CaliberDefinition v2 = registryService.newVersion("AIRLINE",
                new NewCaliberVersionRequest(TimestampSource.DEPARTURE, DenominatorRule.FLOWN_ONLY, 30,
                        "航司口径容差放宽到 30 分钟", "容差 15→30", day, null), "manager");
        assertThat(v2.getVersion()).isEqualTo(2);
        CaliberDefinition v1 = registryService.requireVersion("AIRLINE", 1);
        assertThat(v1.getStatus()).isEqualTo(CaliberStatus.RETIRED);
        assertThat(v2.getStatus()).isEqualTo(CaliberStatus.ACTIVE);

        // 新计算绑定 v2；旧发布运行仍绑定 v1，数字不被追溯改写
        List<CaliberRun> recomputed = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "改版后并行计算");
        CaliberRun newRun = recomputed.stream().filter(r -> r.getCaliberCode().equals("AIRLINE")).findFirst().orElseThrow();
        assertThat(newRun.getCaliberVersion()).isEqualTo(2);
        assertThat(newRun.getToleranceMinutes()).isEqualTo(30);
        CaliberRun stale = runRepository
                .findByServiceDateAndCaliberCodeAndRunNo(day, "AIRLINE", airlineV1.getRunNo()).orElseThrow();
        assertThat(stale.getCaliberVersion()).isEqualTo(1);
        assertThat(stale.getToleranceMinutes()).isEqualTo(15);
        assertThat(stale.getCounted()).isEqualTo(countedBefore);

        // 口径版本变化造成的差额归因于口径定义切换（可解释），允许发布
        var outcome = arbitrationService.publish(day, newRun.getId());
        assertThat(outcome.diffs()).allMatch(d -> d.getAttribution() == DiffAttribution.EXPLAINED);
        assertThat(outcome.diffs().stream().findFirst().orElseThrow().getCausedByChanges())
                .contains("CALIBER_DEF_V1->V2");
    }

    @Test
    void downstreamBindingLedgerRules() {
        List<CaliberRun> runs = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "首次并行计算");
        CaliberRun airport = runs.stream().filter(r -> r.getCaliberCode().equals("AIRPORT")).findFirst().orElseThrow();

        // 月报必须绑定具体物化运行
        DownstreamBinding monthly = bindingService.bind(new BindingRequest(
                DownstreamTarget.MONTHLY_REPORT, day.toString().substring(0, 7),
                "AIRPORT", 1, airport.getId(), day, "锁定月报"), "manager");
        assertThat(monthly.getCaliberVersion()).isEqualTo(1);
        assertThat(monthly.getRunId()).isEqualTo(airport.getId());

        assertThatThrownBy(() -> bindingService.bind(new BindingRequest(
                DownstreamTarget.MONTHLY_REPORT, "2026-12", "AIRPORT", 1, null, day, "缺运行"), "manager"))
                .isInstanceOf(ApiException.class).hasMessageContaining("runId");

        // 大屏可只绑口径版本
        DownstreamBinding screen = bindingService.bind(new BindingRequest(
                DownstreamTarget.SCREEN, null, "AIRLINE", null, null, null, "大屏实时"), "manager");
        assertThat(screen.getCaliberVersion()).isEqualTo(1);

        // 同周期重复绑定：旧绑定失效，台账保留历史；当前生效绑定可查
        CaliberRun airline = runs.stream().filter(r -> r.getCaliberCode().equals("AIRLINE")).findFirst().orElseThrow();
        bindingService.bind(new BindingRequest(
                DownstreamTarget.MONTHLY_REPORT, day.toString().substring(0, 7),
                "AIRLINE", 1, airline.getId(), day, "改引航司口径"), "manager");
        List<DownstreamBinding> history = bindingService.history(DownstreamTarget.MONTHLY_REPORT);
        assertThat(history).hasSize(2);
        assertThat(history.stream().filter(DownstreamBinding::isActive)).hasSize(1);
        assertThat(bindingService.activeBindings())
                .anyMatch(b -> b.getTarget() == DownstreamTarget.SCREEN);

        // 按口径版本反查影响面
        assertThat(bindingService.byCaliberVersion("AIRPORT", 1))
                .anyMatch(b -> b.getTarget() == DownstreamTarget.MONTHLY_REPORT);
    }

    @Test
    void historicalBackfillMarksSegmentsAndScope() {
        LocalDate from = day.minusDays(2);
        // 对历史区间（无实例的空日期也可计算）按机场口径补齐
        var outcome = backfillService.backfillRange(new com.example.flightops.web.dto.BackfillRangeRequest(
                "AIRPORT", from, day, "新口径上线前历史数据补齐"), "manager");
        assertThat(outcome.segments()).hasSize(1);
        assertThat(outcome.segments().get(0).getSegmentType()).isEqualTo(BackfillScope.BACKFILL);
        assertThat(outcome.segments().get(0).getRangeFrom()).isEqualTo(from);
        assertThat(outcome.runs()).isNotEmpty();
        assertThat(outcome.runs()).allMatch(r -> r.getBackfillScope() == BackfillScope.BACKFILL);

        // 区间台账可查，与当天 ORIGINAL 运行分列
        List<?> segments = backfillService.segments("AIRPORT");
        assertThat(segments).hasSize(1);
        List<CaliberRun> dayRuns = runRepository.findByServiceDateOrderByCaliberCodeAscRunNoAsc(day);
        assertThat(dayRuns).anyMatch(r -> r.getBackfillScope() == BackfillScope.BACKFILL);
    }
}
