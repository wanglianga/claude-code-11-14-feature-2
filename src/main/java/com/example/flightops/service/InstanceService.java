package com.example.flightops.service;

import com.example.flightops.domain.ChangeEventType;
import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.ChangeLogId;
import com.example.flightops.domain.ChangeSource;
import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.domain.ScheduleVersion;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.FlightInstanceRepository;
import com.example.flightops.repo.ScheduleVersionRepository;
import com.example.flightops.web.dto.RegisterInstanceRequest;
import com.example.flightops.web.dto.ScheduleChangeRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 实例登记与计划时刻版本管理。 */
@Service
public class InstanceService {

    private final FlightInstanceRepository instanceRepository;
    private final ScheduleVersionRepository scheduleRepository;
    private final ChangeLogRepository changeLogRepository;

    public InstanceService(FlightInstanceRepository instanceRepository,
                           ScheduleVersionRepository scheduleRepository,
                           ChangeLogRepository changeLogRepository) {
        this.instanceRepository = instanceRepository;
        this.scheduleRepository = scheduleRepository;
        this.changeLogRepository = changeLogRepository;
    }

    /** 登记实例：主键 = 航班号 + 执行日期 + 航段号；同时写入首版计划与 REGISTER 变更日志。 */
    @Transactional
    public FlightInstance register(RegisterInstanceRequest request) {
        FlightInstanceId id = new FlightInstanceId(request.flightNo(), request.serviceDate(), request.legNoOrDefault());
        if (instanceRepository.existsById(id)) {
            throw ApiException.conflict("航班实例已存在: " + id + "（补班/加班/不同航段请使用不同航段号或实例类型）");
        }
        FlightInstance instance = new FlightInstance(id, request.origin(), request.destination(), request.kindOrDefault());
        instance.setCurrentVersion(1);
        instanceRepository.save(instance);

        ScheduleVersion v1 = new ScheduleVersion(id, 1, request.schedDeparture(), request.schedArrival(),
                request.effectiveFrom() != null ? request.effectiveFrom() : Instant.now(), "SCHEDULE");
        scheduleRepository.save(v1);

        changeLogRepository.save(new ChangeLogEntry(
                new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), 1),
                ChangeEventType.REGISTER, null, FlightStatus.PLANNED, (short) 1,
                Instant.now(), ChangeSource.SCHEDULE, false,
                "实例登记，计划版本 v1"));
        return instance;
    }

    /** 航司改计划：追加新计划版本并写审计日志；已发布的放行结论不回写。 */
    @Transactional
    public ScheduleVersion changeSchedule(FlightInstanceId id, ScheduleChangeRequest request) {
        FlightInstance instance = instanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("航班实例不存在: " + id));
        List<ScheduleVersion> versions = scheduleRepository.findByFlightNoAndServiceDateAndLegNoOrderByVersionAsc(
                id.getFlightNo(), id.getServiceDate(), id.getLegNo());
        int nextVersion = versions.stream().mapToInt(ScheduleVersion::getVersion).max().orElse(0) + 1;

        ScheduleVersion next = new ScheduleVersion(id, nextVersion, request.schedDeparture(), request.schedArrival(),
                request.effectiveFrom() != null ? request.effectiveFrom() : Instant.now(),
                request.source() != null ? request.source() : "SCHEDULE");
        scheduleRepository.save(next);

        int version = instance.getCurrentVersion();
        changeLogRepository.saveAll(List.of(
                new ChangeLogEntry(new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), ++version),
                        ChangeEventType.SCHEDULE_CHANGE, null, null, (short) -1, Instant.now(),
                        ChangeSource.SCHEDULE, false, "计划版本 v" + (nextVersion - 1) + " 失效"),
                new ChangeLogEntry(new ChangeLogId(id.getFlightNo(), id.getServiceDate(), id.getLegNo(), ++version),
                        ChangeEventType.SCHEDULE_CHANGE, null, null, (short) 1, Instant.now(),
                        ChangeSource.SCHEDULE, false,
                        "计划版本 v" + nextVersion + " 生效: 起飞 " + request.schedDeparture())));
        instance.setCurrentVersion(version);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);
        return next;
    }
}
