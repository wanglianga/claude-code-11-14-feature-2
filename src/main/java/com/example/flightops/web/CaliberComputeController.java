package com.example.flightops.web;

import com.example.flightops.domain.CaliberCompensation;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.service.CaliberBackfillService;
import com.example.flightops.service.CaliberQueryService;
import com.example.flightops.service.CaliberComputeEngine;
import com.example.flightops.domain.BackfillScope;
import com.example.flightops.web.dto.BackfillRangeRequest;
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

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 并行计算引擎：同一批变更事件按每个生效口径各产一份带版本的物化结果；
 * 超窗补录由事件摄入自动逐口径补偿，这里提供手动并行计算、历史补齐与审计查询。
 */
@Tag(name = "多口径-并行计算", description = "同一批事件并行产出多套带版本物化结果、补偿留痕与补齐区间")
@RestController
@RequestMapping("/api/caliber-runs")
public class CaliberComputeController {

    private final CaliberComputeEngine computeEngine;
    private final CaliberQueryService queryService;
    private final CaliberBackfillService backfillService;

    public CaliberComputeController(CaliberComputeEngine computeEngine,
                                    CaliberQueryService queryService,
                                    CaliberBackfillService backfillService) {
        this.computeEngine = computeEngine;
        this.queryService = queryService;
        this.backfillService = backfillService;
    }

    @Operation(summary = "并行计算：当天全部生效口径各产出一份物化运行（口径定义与结果双版本绑定）")
    @PostMapping("/compute/{date}")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CaliberRun> computeParallel(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return computeEngine.computeParallel(date, BackfillScope.ORIGINAL, "常规并行计算");
    }

    @Operation(summary = "当天各口径全部物化运行（含被补偿取代的历史运行）")
    @GetMapping("/{date}")
    public List<CaliberRun> runs(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queryService.runs(date);
    }

    @Operation(summary = "指定运行明细：逐航班物化结论与时间戳来源事实时间")
    @GetMapping("/{date}/runs/{runId}")
    public Map<String, Object> runDetail(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable Long runId) {
        return queryService.runDetail(date, runId);
    }

    @Operation(summary = "同航班跨口径对比：机场判正常/航司判延误可逐行验证，差额定位到推出与起飞之间的等待")
    @GetMapping("/{date}/flights/{flightNo}/legs/{legNo}/compare")
    public Map<String, Object> crossCaliber(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable String flightNo,
            @PathVariable int legNo) {
        return queryService.crossCaliberFlight(date, flightNo, legNo);
    }

    @Operation(summary = "补偿留痕：每次窗口内更新/超窗补录对各口径的逐口径补偿记录")
    @GetMapping("/{date}/compensations")
    public List<CaliberCompensation> compensations(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queryService.compensations(date);
    }

    @Operation(summary = "单航班补偿留痕")
    @GetMapping("/{date}/flights/{flightNo}/legs/{legNo}/compensations")
    public List<CaliberCompensation> flightCompensations(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable String flightNo,
            @PathVariable int legNo) {
        return queryService.compensationsForFlight(date, flightNo, legNo);
    }

    @Operation(summary = "分区分叉审计：返回未覆盖全部口径的补偿触发组（空数组=无分叉）")
    @GetMapping("/{date}/fork-audit")
    public List<Map<String, Object>> forkAudit(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queryService.forkAudit(date);
    }

    @Operation(summary = "历史补齐：按口径对区间补算，补齐区间与原始区间分列标注")
    @PostMapping("/backfill")
    @ResponseStatus(HttpStatus.CREATED)
    public CaliberBackfillService.BackfillOutcome backfill(@Valid @RequestBody BackfillRangeRequest request,
                                                           Principal principal) {
        return backfillService.backfillRange(request, principal != null ? principal.getName() : "manager");
    }

    @Operation(summary = "补齐区间标注台账（哪版口径、何时切换影响哪些航班的区间依据）")
    @GetMapping("/backfill/segments")
    public List<?> segments(@RequestParam(required = false) String caliberCode) {
        return backfillService.segments(caliberCode);
    }
}
