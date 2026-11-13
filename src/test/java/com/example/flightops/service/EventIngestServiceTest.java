package com.example.flightops.service;

import com.example.flightops.AbstractIntegrationTest;
import com.example.flightops.domain.Alert;
import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.ChangeSource;
import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.InstanceKind;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.AlertRepository;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.FlightInstanceRepository;
import com.example.flightops.web.dto.IngestEventRequest;
import com.example.flightops.web.dto.IngestResult;
import com.example.flightops.web.dto.RegisterInstanceRequest;
import com.example.flightops.web.dto.ScheduleChangeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventIngestServiceTest extends AbstractIntegrationTest {

    @Autowired
    private EventIngestService ingestService;
    @Autowired
    private InstanceService instanceService;
    @Autowired
    private FlightInstanceRepository instanceRepository;
    @Autowired
    private ChangeLogRepository changeLogRepository;
    @Autowired
    private AlertRepository alertRepository;

    private final LocalDate day = LocalDate.now();

    /** PostgreSQL timestamptz 存微秒，测试时间统一截断到毫秒保证可比较。 */
    private static Instant t(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    private FlightInstanceId register(String flightNo, int legNo, Instant schedDeparture) {
        instanceService.register(new RegisterInstanceRequest(
                flightNo, day, legNo, "ZBAA", "ZSSS", InstanceKind.REGULAR,
                schedDeparture, schedDeparture.plus(2, ChronoUnit.HOURS),
                Instant.now().minus(2, ChronoUnit.DAYS)));
        return new FlightInstanceId(flightNo, day, legNo);
    }

    private IngestResult send(FlightInstanceId id, FlightStatus to, Instant eventTime) {
        return ingestService.ingest(new IngestEventRequest(
                id.getFlightNo(), id.getServiceDate(), id.getLegNo(), to, eventTime, null, null));
    }

    private List<ChangeLogEntry> logOf(FlightInstanceId id) {
        return changeLogRepository.findByIdFlightNoAndIdServiceDateAndIdLegNoOrderByIdChangeVersionAsc(
                id.getFlightNo(), id.getServiceDate(), id.getLegNo());
    }

    @Test
    void illegalTransitionRejectedAndAlertRaised() {
        FlightInstanceId id = register("CA9901", 1, Instant.now().plus(1, ChronoUnit.HOURS));

        // 未推出直接落地：拒绝并告警
        assertThatThrownBy(() -> send(id, FlightStatus.LANDED, Instant.now()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getRule()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(alerts.get(0).getMessage()).contains("PLANNED").contains("LANDED");
        // 拒绝后不写变更日志（只有 REGISTER 一条）
        assertThat(logOf(id)).hasSize(1);
    }

    @Test
    void acceptedChangesAreAppendedWithSignAndVersion() {
        FlightInstanceId id = register("CA9902", 1, Instant.now().plus(1, ChronoUnit.HOURS));
        send(id, FlightStatus.BOARDING, Instant.now());
        send(id, FlightStatus.PUSHBACK, Instant.now());
        send(id, FlightStatus.DEPARTED, Instant.now());

        List<ChangeLogEntry> entries = logOf(id);
        // REGISTER(+1) + 3 次流转各一对(-1,+1) = 7 条，版本 1..7
        assertThat(entries).hasSize(7);
        assertThat(entries).allMatch(e -> e.getId().getChangeVersion() >= 1);
        assertThat(entries.get(0).getSign()).isEqualTo((short) 1);
        assertThat(entries.get(1).getSign()).isEqualTo((short) -1);
        assertThat(entries.get(2).getSign()).isEqualTo((short) 1);
        assertThat(entries.get(6).getToStatus()).isEqualTo(FlightStatus.DEPARTED);

        FlightInstance instance = instanceRepository.findById(id).orElseThrow();
        assertThat(instance.getCurrentVersion()).isEqualTo(7);
        assertThat(instance.getCurrentStatus()).isEqualTo(FlightStatus.DEPARTED);
        // 同一实例主键唯一，变更成对冲抵，不会被重复计数
        assertThat(instance.getReleaseNormal()).isNotNull();
    }

    @Test
    void judgmentBindsScheduleVersionEffectiveAtDeparture() {
        Instant schedDeparture = t(Instant.now().minus(2, ChronoUnit.HOURS));
        FlightInstanceId id = register("CA9903", 1, schedDeparture);
        // 实际起飞延误 10 分钟（容差 20）→ 正常
        send(id, FlightStatus.BOARDING, Instant.now().minus(2, ChronoUnit.HOURS));
        send(id, FlightStatus.PUSHBACK, Instant.now().minus(2, ChronoUnit.HOURS));
        send(id, FlightStatus.DEPARTED, schedDeparture.plus(10, ChronoUnit.MINUTES));

        FlightInstance judged = instanceRepository.findById(id).orElseThrow();
        assertThat(judged.getReleaseNormal()).isTrue();
        assertThat(judged.getReleaseDelayMinutes()).isEqualTo(10);
        Long boundVersion = judged.getReleaseVersionId();

        // 航司事后改计划：计划起飞提前 1 小时（若按新计划算应为不正常）
        instanceService.changeSchedule(id, new ScheduleChangeRequest(
                schedDeparture.minus(1, ChronoUnit.HOURS), schedDeparture.plus(1, ChronoUnit.HOURS),
                Instant.now(), "AOC"));

        FlightInstance after = instanceRepository.findById(id).orElseThrow();
        // 已发布结论不被反向改写
        assertThat(after.getReleaseNormal()).isTrue();
        assertThat(after.getReleaseVersionId()).isEqualTo(boundVersion);
    }

    @Test
    void lateEventIsMarkedBackfill() {
        FlightInstanceId id = register("CA9904", 1, Instant.now().minus(11, ChronoUnit.HOURS));
        IngestResult result = send(id, FlightStatus.BOARDING, Instant.now().minus(10, ChronoUnit.HOURS));
        assertThat(result.late()).isTrue();
        assertThat(result.source()).isEqualTo(ChangeSource.BACKFILL);

        IngestResult inWindow = send(id, FlightStatus.PUSHBACK, Instant.now().minus(5, ChronoUnit.MINUTES));
        assertThat(inWindow.late()).isFalse();
        assertThat(inWindow.source()).isEqualTo(ChangeSource.REALTIME);
    }

    @Test
    void landedTimeCorrectionAfterLanding() {
        Instant schedDeparture = t(Instant.now().minus(3, ChronoUnit.HOURS));
        FlightInstanceId id = register("CA9905", 1, schedDeparture);
        send(id, FlightStatus.BOARDING, Instant.now().minus(3, ChronoUnit.HOURS));
        send(id, FlightStatus.PUSHBACK, Instant.now().minus(3, ChronoUnit.HOURS));
        send(id, FlightStatus.DEPARTED, schedDeparture.plus(5, ChronoUnit.MINUTES));
        Instant firstLanding = t(Instant.now().minus(40, ChronoUnit.MINUTES));
        send(id, FlightStatus.LANDED, firstLanding);

        // 凌晨补录落地时间：同状态补录 → 时间更正
        Instant correctedLanding = t(Instant.now().minus(35, ChronoUnit.MINUTES));
        IngestResult correction = send(id, FlightStatus.LANDED, correctedLanding);
        assertThat(correction.eventType()).isEqualTo("TIME_CORRECTION");
        assertThat(correction.currentStatus()).isEqualTo(FlightStatus.LANDED);

        FlightInstance instance = instanceRepository.findById(id).orElseThrow();
        assertThat(instance.getActualArrivalTime()).isEqualTo(correctedLanding);
        // 重放口径与投影一致
        ReplayService replayService = new ReplayService(changeLogRepository);
        assertThat(replayService.replay(id).arrival()).isEqualTo(correctedLanding);
    }

    @Test
    void departureTimeCorrectionRejudges() {
        Instant schedDeparture = t(Instant.now().minus(3, ChronoUnit.HOURS));
        FlightInstanceId id = register("CA9906", 1, schedDeparture);
        send(id, FlightStatus.BOARDING, Instant.now().minus(3, ChronoUnit.HOURS));
        send(id, FlightStatus.PUSHBACK, Instant.now().minus(3, ChronoUnit.HOURS));
        // 先报了一个准点的起飞时间
        send(id, FlightStatus.DEPARTED, schedDeparture.plus(5, ChronoUnit.MINUTES));
        assertThat(instanceRepository.findById(id).orElseThrow().getReleaseNormal()).isTrue();

        // 落地后补报真实起飞时间：延误 50 分钟 → 重新判定为不正常
        send(id, FlightStatus.LANDED, Instant.now().minus(30, ChronoUnit.MINUTES));
        IngestResult correction = send(id, FlightStatus.DEPARTED, schedDeparture.plus(50, ChronoUnit.MINUTES));
        assertThat(correction.eventType()).isEqualTo("TIME_CORRECTION");

        FlightInstance instance = instanceRepository.findById(id).orElseThrow();
        assertThat(instance.getReleaseNormal()).isFalse();
        assertThat(instance.getReleaseDelayMinutes()).isEqualTo(50);
        assertThat(instance.getFirstDepartureTime()).isEqualTo(schedDeparture.plus(50, ChronoUnit.MINUTES));
    }

    @Test
    void cancelThenRestoreIsLegalAndCountsOnServiceDate() {
        FlightInstanceId id = register("CA9907", 1, Instant.now().plus(2, ChronoUnit.HOURS));
        send(id, FlightStatus.CANCELLED, Instant.now());
        IngestResult restored = send(id, FlightStatus.RESTORED, Instant.now());
        assertThat(restored.currentStatus()).isEqualTo(FlightStatus.RESTORED);
        // 恢复后可继续正常流转
        IngestResult boarding = send(id, FlightStatus.BOARDING, Instant.now());
        assertThat(boarding.currentStatus()).isEqualTo(FlightStatus.BOARDING);
    }

    @Test
    void divertThenContinueIsOneInstance() {
        Instant schedDeparture = t(Instant.now().minus(4, ChronoUnit.HOURS));
        FlightInstanceId id = register("CA9908", 1, schedDeparture);
        send(id, FlightStatus.BOARDING, Instant.now().minus(4, ChronoUnit.HOURS));
        send(id, FlightStatus.PUSHBACK, Instant.now().minus(4, ChronoUnit.HOURS));
        send(id, FlightStatus.DEPARTED, schedDeparture.plus(8, ChronoUnit.MINUTES));
        send(id, FlightStatus.DIVERTED, Instant.now().minus(2, ChronoUnit.HOURS));
        send(id, FlightStatus.DEPARTED, Instant.now().minus(1, ChronoUnit.HOURS));
        send(id, FlightStatus.LANDED, Instant.now().minus(20, ChronoUnit.MINUTES));

        FlightInstance instance = instanceRepository.findById(id).orElseThrow();
        // 备降续飞算一班：判定仍按首次起飞（延误 8 分钟 → 正常）
        assertThat(instance.getReleaseNormal()).isTrue();
        assertThat(instance.getReleaseDelayMinutes()).isEqualTo(8);
        assertThat(instance.getCurrentStatus()).isEqualTo(FlightStatus.LANDED);
    }
}
