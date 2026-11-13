package com.example.flightops.web;

import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.domain.ScheduleVersion;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.FlightInstanceRepository;
import com.example.flightops.repo.ScheduleVersionRepository;
import com.example.flightops.service.InstanceService;
import com.example.flightops.web.dto.RegisterInstanceRequest;
import com.example.flightops.web.dto.ScheduleChangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "航班实例", description = "实例登记、查询、计划版本与变更日志")
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceService instanceService;
    private final FlightInstanceRepository instanceRepository;
    private final ScheduleVersionRepository scheduleRepository;
    private final ChangeLogRepository changeLogRepository;

    public InstanceController(InstanceService instanceService,
                              FlightInstanceRepository instanceRepository,
                              ScheduleVersionRepository scheduleRepository,
                              ChangeLogRepository changeLogRepository) {
        this.instanceService = instanceService;
        this.instanceRepository = instanceRepository;
        this.scheduleRepository = scheduleRepository;
        this.changeLogRepository = changeLogRepository;
    }

    @Operation(summary = "登记航班实例（主键=航班号+执行日期+航段号，含首版计划）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightInstance register(@Valid @RequestBody RegisterInstanceRequest request) {
        return instanceService.register(request);
    }

    @Operation(summary = "按执行日期查询实例列表")
    @GetMapping
    public List<FlightInstance> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return instanceRepository.findByIdServiceDateOrderByIdFlightNoAscIdLegNoAsc(date);
    }

    @Operation(summary = "实例详情（含计划版本列表）")
    @GetMapping("/{flightNo}/{serviceDate}/{legNo}")
    public Map<String, Object> detail(@PathVariable String flightNo,
                                      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
                                      @PathVariable int legNo) {
        FlightInstanceId id = new FlightInstanceId(flightNo, serviceDate, legNo);
        FlightInstance instance = instanceRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("航班实例不存在: " + id));
        List<ScheduleVersion> versions = scheduleRepository
                .findByFlightNoAndServiceDateAndLegNoOrderByVersionAsc(flightNo, serviceDate, legNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", instance);
        result.put("scheduleVersions", versions);
        return result;
    }

    @Operation(summary = "实例的变更日志（带正负号与版本，下游按主键逐条冲抵）")
    @GetMapping("/{flightNo}/{serviceDate}/{legNo}/changes")
    public List<ChangeLogEntry> changes(@PathVariable String flightNo,
                                        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
                                        @PathVariable int legNo) {
        return changeLogRepository.findByIdFlightNoAndIdServiceDateAndIdLegNoOrderByIdChangeVersionAsc(
                flightNo, serviceDate, legNo);
    }

    @Operation(summary = "航司计划时刻变更（产生新版本，不回写已发布结论）")
    @PostMapping("/{flightNo}/{serviceDate}/{legNo}/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleVersion changeSchedule(@PathVariable String flightNo,
                                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
                                          @PathVariable int legNo,
                                          @Valid @RequestBody ScheduleChangeRequest request) {
        return instanceService.changeSchedule(new FlightInstanceId(flightNo, serviceDate, legNo), request);
    }
}
