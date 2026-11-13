package com.example.flightops.service;

import com.example.flightops.AbstractIntegrationTest;
import com.example.flightops.domain.BackfillScope;
import com.example.flightops.domain.CaliberCompensation;
import com.example.flightops.domain.CaliberResultItem;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.CaliberRunStatus;
import com.example.flightops.domain.CompensationKind;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.InstanceKind;
import com.example.flightops.repo.CaliberCompensationRepository;
import com.example.flightops.repo.CaliberResultItemRepository;
import com.example.flightops.repo.CaliberRunRepository;
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

/**
 * 多口径并行计算故事线：
 * 同一航班机场（推出口径）判正常、航司（起飞口径）判延误；集团第三套分母规则；
 * 同一批事件并行产出三套带版本物化结果；超窗补录向所有口径逐口径补偿、无分区分叉。
 */
class CaliberSubsystemTest extends AbstractIntegrationTest {

    @Autowired private CaliberComputeEngine computeEngine;
    @Autowired private CaliberQueryService queryService;
    @Autowired private CaliberReleaseArbitrationService arbitrationService;
    @Autowired private InstanceService instanceService;
    @Autowired private EventIngestService ingestService;
    @Autowired private CaliberRunRepository runRepository;
    @Autowired private CaliberResultItemRepository itemRepository;
    @Autowired private CaliberCompensationRepository compensationRepository;

    private final LocalDate day = LocalDate.now();

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

    private CaliberResultItem itemOf(CaliberRun run, String flightNo, int legNo) {
        return itemRepository.findByIdRunIdOrderByIdFlightNoAscIdLegNoAsc(run.getId()).stream()
                .filter(i -> i.getId().getFlightNo().equals(flightNo) && i.getId().getLegNo() == legNo)
                .findFirst().orElseThrow();
    }

    private CaliberRun runOf(List<CaliberRun> runs, String code) {
        return runs.stream().filter(r -> r.getCaliberCode().equals(code)).findFirst().orElseThrow();
    }

    /**
     * CA9001：推出仅晚 10 分钟（机场容差 20 → 正常），起飞晚 40 分钟（航司容差 15 → 延误），
     * 落地准点（集团到达口径 → 正常）；MU9002 取消；CZ9003 应飞未飞。
     */
    @BeforeEach
    void buildDay() {
        Instant now = t(Instant.now());
        Instant s1 = now.minus(4, ChronoUnit.HOURS);
        FlightInstanceId ca = register("CA9001", 1, InstanceKind.REGULAR, s1);
        send(ca, FlightStatus.BOARDING, s1.minus(30, ChronoUnit.MINUTES));
        send(ca, FlightStatus.PUSHBACK, s1.plus(10, ChronoUnit.MINUTES));   // 推出 +10
        send(ca, FlightStatus.DEPARTED, s1.plus(40, ChronoUnit.MINUTES));  // 起飞 +40（跑道等待 30 分钟）
        send(ca, FlightStatus.LANDED, s1.plus(2, ChronoUnit.HOURS));       // 落地准点

        FlightInstanceId mu = register("MU9002", 1, InstanceKind.REGULAR, now.minus(2, ChronoUnit.HOURS));
        send(mu, FlightStatus.CANCELLED, now.minus(90, ChronoUnit.MINUTES));

        register("CZ9003", 1, InstanceKind.EXTRA, now.minus(11, ChronoUnit.HOURS)); // 全天无事件
    }

    @Test
    void parallelComputeProducesThreeVersionedMaterializedRuns() {
        List<CaliberRun> runs = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "常规并行计算");
        assertThat(runs).hasSize(3);
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.getCaliberVersion()).isEqualTo(1);
            assertThat(r.getStatus()).isEqualTo(CaliberRunStatus.COMPUTED);
            assertThat(r.getBackfillScope()).isEqualTo(BackfillScope.ORIGINAL);
            assertThat(r.getRunNo()).isEqualTo(1);
        });

        // 机场口径 FLOWN_PLUS_MISSING：分母 = CA9001 + CZ9003 = 2（取消不计），正常仅 CA9001
        CaliberRun airport = runOf(runs, "AIRPORT");
        assertThat(airport.getCounted()).isEqualTo(2);
        assertThat(airport.getNormal()).isEqualTo(1);
        assertThat(airport.getRate()).isEqualByComparingTo(new BigDecimal("50.00"));

        // 航司口径 FLOWN_ONLY：分母仅已起飞的 CA9001 = 1，且判不正常 → 正常率 0
        CaliberRun airline = runOf(runs, "AIRLINE");
        assertThat(airline.getCounted()).isEqualTo(1);
        assertThat(airline.getNormal()).isZero();
        assertThat(airline.getRate()).isEqualByComparingTo(new BigDecimal("0.00"));

        // 集团口径 ALL_REGISTERED：3 个登记实例全计入，仅 CA9001 到达准点正常
        CaliberRun group = runOf(runs, "GROUP");
        assertThat(group.getCounted()).isEqualTo(3);
        assertThat(group.getNormal()).isEqualTo(1);
        assertThat(group.getRate()).isEqualByComparingTo(new BigDecimal("33.33"));
    }

    @Test
    void sameFlightAirportNormalButAirlineDelayed() {
        List<CaliberRun> runs = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "常规并行计算");

        CaliberResultItem airportItem = itemOf(runOf(runs, "AIRPORT"), "CA9001", 1);
        CaliberResultItem airlineItem = itemOf(runOf(runs, "AIRLINE"), "CA9001", 1);
        CaliberResultItem groupItem = itemOf(runOf(runs, "GROUP"), "CA9001", 1);

        // 机场看推出：+10 分钟在 20 分钟容差内 → 正常
        assertThat(airportItem.isCounted()).isTrue();
        assertThat(airportItem.getNormal()).isTrue();
        assertThat(airportItem.getDelayMinutes()).isEqualTo(10);
        // 航司看起飞：+40 分钟超 15 分钟容差 → 延误（差额正是推出与起飞之间 30 分钟等待）
        assertThat(airlineItem.getNormal()).isFalse();
        assertThat(airlineItem.getDelayMinutes()).isEqualTo(40);
        // 集团看到达：落地准点 → 正常，延误 0
        assertThat(groupItem.getNormal()).isTrue();
        assertThat(groupItem.getDelayMinutes()).isZero();

        // 跨口径对比端点同结论
        Map<String, Object> compared = queryService.crossCaliberFlight(day, "CA9001", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) compared.get("caliberResults");
        assertThat(rows).hasSize(3);
        Map<String, Object> airlineRow = rows.stream()
                .filter(r -> "AIRLINE".equals(r.get("caliberCode"))).findFirst().orElseThrow();
        assertThat(airlineRow.get("normal")).isEqualTo(false);
        assertThat(airlineRow.get("timestampSource")).isEqualTo(com.example.flightops.domain.TimestampSource.DEPARTURE);
    }

    @Test
    void lateBackfillCompensatesEveryCaliberAndLeavesNoFork() {
        List<CaliberRun> first = computeEngine.computeParallel(day, BackfillScope.ORIGINAL, "常规并行计算");
        first.forEach(r -> arbitrationService.publish(day, r.getId()));

        // 超窗补录：CZ9003 实际已起飞（事件时间 11 小时前，超 6 小时窗口 → BACKFILL）
        FlightInstanceId cz = new FlightInstanceId("CZ9003", day, 1);
        Instant departed = t(Instant.now().minus(11, ChronoUnit.HOURS).plus(5, ChronoUnit.MINUTES));
        send(cz, FlightStatus.BOARDING, departed.minus(40, ChronoUnit.MINUTES));
        send(cz, FlightStatus.PUSHBACK, departed.minus(10, ChronoUnit.MINUTES));
        send(cz, FlightStatus.DEPARTED, departed);

        // 每个口径各留一条补偿记录（共 3 次事件 × 3 口径 = 9 条），逐口径留痕
        List<CaliberCompensation> compensations = queryService.compensations(day);
        assertThat(compensations).isNotEmpty();
        assertThat(compensations).allSatisfy(c -> {
            assertThat(c.getKind()).isEqualTo(CompensationKind.LATE_BACKFILL);
            assertThat(c.isLate()).isTrue();
            assertThat(c.getToRunId()).isNotNull();
            assertThat(c.getFromRunId()).isNotNull();
        });
        for (String code : List.of("AIRPORT", "AIRLINE", "GROUP")) {
            assertThat(compensations.stream().filter(c -> c.getCaliberCode().equals(code)).count())
                    .isEqualTo(3);
        }

        // 分区分叉审计：每次补偿都覆盖全部口径 → 无分叉
        assertThat(queryService.forkAudit(day)).isEmpty();

        // 每口径产生 3 个补偿候选运行（runNo 2-4），原发布运行保持 PUBLISHED 作为仲裁基线
        List<CaliberRun> allRuns = runRepository.findByServiceDateOrderByCaliberCodeAscRunNoAsc(day);
        for (String code : List.of("AIRPORT", "AIRLINE", "GROUP")) {
            List<CaliberRun> perCaliber = allRuns.stream()
                    .filter(r -> r.getCaliberCode().equals(code)).toList();
            assertThat(perCaliber).hasSize(4);
            assertThat(perCaliber.get(0).getStatus()).isEqualTo(CaliberRunStatus.PUBLISHED);
            assertThat(perCaliber.get(3).getRunNo()).isEqualTo(4);
            assertThat(perCaliber.get(3).getBackfillScope()).isEqualTo(BackfillScope.BACKFILL);
        }

        // 航司口径补齐后分母从 1 变 2（CZ9003 起飞延误 5 分钟在容差内 → 正常）
        CaliberRun airlineLatest = runRepository
                .findTopByServiceDateAndCaliberCodeOrderByRunNoDesc(day, "AIRLINE").orElseThrow();
        assertThat(airlineLatest.getRunNo()).isEqualTo(4);
        assertThat(airlineLatest.getCounted()).isEqualTo(2);
        assertThat(airlineLatest.getNormal()).isEqualTo(1);
        CaliberResultItem czItem = itemOf(airlineLatest, "CZ9003", 1);
        assertThat(czItem.getBackfillScope()).isEqualTo(BackfillScope.BACKFILL);
        assertThat(czItem.getNormal()).isTrue();

        // 发布补偿后的新运行：与 PUBLISHED 基线逐航班比对，差额可归因到具体变更版本 → 发布成功
        var outcome = arbitrationService.publish(day, airlineLatest.getId());
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.diffs()).isNotEmpty();
        assertThat(outcome.diffs()).allMatch(d -> d.getAttribution().name().equals("EXPLAINED"));
        assertThat(outcome.diffs().stream().findFirst().orElseThrow().getCausedByChanges()).isNotBlank();

        // 仲裁通过后旧基线才被取代，候选运行成为新的发布结果（服务在独立事务提交，需重新查询）
        assertThat(runRepository.findByServiceDateAndCaliberCodeAndRunNo(day, "AIRLINE", 1)
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.SUPERSEDED);
        assertThat(runRepository.findById(
                new com.example.flightops.domain.CaliberRunId(airlineLatest.getId(), day))
                .orElseThrow().getStatus()).isEqualTo(CaliberRunStatus.PUBLISHED);
    }

    @Test
    void compensationRepositoryIsWiredWithSequence() {
        // 序列与分区复合主键可用（补偿 id 由应用侧显式赋值）
        assertThat(compensationRepository.nextId()).isPositive();
    }
}
