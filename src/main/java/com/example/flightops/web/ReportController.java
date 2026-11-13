package com.example.flightops.web;

import com.example.flightops.domain.DailyReport;
import com.example.flightops.domain.ReportCorrection;
import com.example.flightops.domain.ReportDiff;
import com.example.flightops.service.ReportService;
import com.example.flightops.web.dto.RecomputeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.List;
import java.util.Map;

@Tag(name = "日报", description = "日报重算、版本、锁定、更正与差异定位")
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "重算日报（同一批事件重放；已锁定日期必须 kind=CORRECTION 并填 reason）")
    @PostMapping("/{date}/recompute")
    @ResponseStatus(HttpStatus.CREATED)
    public DailyReport recompute(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @RequestBody(required = false) RecomputeRequest request) {
        String kind = request != null ? request.kind() : null;
        String reason = request != null ? request.reason() : null;
        return reportService.recompute(date, kind, reason);
    }

    @Operation(summary = "锁定该日期最新版本（锁定后更正另出一份）")
    @PostMapping("/{date}/lock")
    public DailyReport lock(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.lock(date);
    }

    @Operation(summary = "该日期全部日报版本（含日报口径与实时口径分列的聚合率）")
    @GetMapping("/{date}")
    public List<DailyReport> versions(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.versions(date);
    }

    @Operation(summary = "指定版本明细：逐航班日报口径与实时口径分列")
    @GetMapping("/{date}/versions/{version}")
    public Map<String, Object> versionDetail(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable int version) {
        return reportService.versionDetail(date, version);
    }

    @Operation(summary = "版本间逐航班差异（超阈值定位到具体那次变更）")
    @GetMapping("/{date}/diff")
    public List<ReportDiff> diffs(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @RequestParam int from,
                                  @RequestParam int to) {
        return reportService.diffs(date, from, to);
    }

    @Operation(summary = "更正记录（原值、更正值、原因同时可查）")
    @GetMapping("/{date}/corrections")
    public List<ReportCorrection> corrections(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.corrections(date);
    }
}
