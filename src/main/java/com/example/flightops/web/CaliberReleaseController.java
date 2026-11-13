package com.example.flightops.web;

import com.example.flightops.domain.CaliberReleaseDiff;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.CaliberSwitchConflict;
import com.example.flightops.domain.CaliberSwitchReview;
import com.example.flightops.service.CaliberReleaseArbitrationService;
import com.example.flightops.web.dto.ReviewDecisionRequest;
import com.example.flightops.web.dto.SwitchReviewRequest;
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
 * 发布仲裁：发布前逐航班比对差额并归因（不可解释则拦截）；
 * 主口径切换走评审，撞日报锁定则锁定优先、顺延并留冲突记录。
 */
@Tag(name = "多口径-发布仲裁", description = "结果发布、差额归因拦截、主口径切换评审与锁定冲突顺延")
@RestController
@RequestMapping("/api/caliber-releases")
public class CaliberReleaseController {

    private final CaliberReleaseArbitrationService arbitrationService;

    public CaliberReleaseController(CaliberReleaseArbitrationService arbitrationService) {
        this.arbitrationService = arbitrationService;
    }

    @Operation(summary = "发布物化运行：逐航班比对新旧结果并归因，存在不可解释差额时 409 拦截")
    @PostMapping("/{date}/runs/{runId}/publish")
    public CaliberReleaseArbitrationService.PublishOutcome publish(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable Long runId) {
        return arbitrationService.publish(date, runId);
    }

    @Operation(summary = "发布差额明细（EXPLAINED 已归因 / UNEXPLAINED 拦截原因）")
    @GetMapping("/{date}/runs/{runId}/diffs")
    public List<CaliberReleaseDiff> diffs(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable Long runId) {
        return arbitrationService.releaseDiffs(date, runId);
    }

    @Operation(summary = "发起主口径切换评审")
    @PostMapping("/switch-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public CaliberSwitchReview propose(@Valid @RequestBody SwitchReviewRequest request, Principal principal) {
        return arbitrationService.proposeSwitch(request, principal != null ? principal.getName() : "manager");
    }

    @Operation(summary = "评审决定：批准（撞锁定则顺延并留冲突记录）/ 驳回")
    @PostMapping("/switch-reviews/{reviewId}/decision")
    public CaliberSwitchReview decide(@PathVariable Long reviewId,
                                      @Valid @RequestBody ReviewDecisionRequest request,
                                      Principal principal) {
        return arbitrationService.decide(reviewId, request, principal != null ? principal.getName() : "manager");
    }

    @Operation(summary = "顺延到期后落地切换（冲突记录同步标记已解决）")
    @PostMapping("/switch-reviews/{reviewId}/resolve")
    public CaliberSwitchReview resolve(@PathVariable Long reviewId, Principal principal) {
        return arbitrationService.resolveDeferred(reviewId, principal != null ? principal.getName() : "manager");
    }

    @Operation(summary = "切换评审列表（可按业务日期过滤）")
    @GetMapping("/switch-reviews")
    public List<CaliberSwitchReview> reviews(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return date == null ? arbitrationService.allReviews() : arbitrationService.reviews(date);
    }

    @Operation(summary = "切换冲突记录（撞日报锁定、顺延留痕）")
    @GetMapping("/switch-conflicts")
    public List<CaliberSwitchConflict> conflicts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            return List.of();
        }
        return arbitrationService.conflicts(date);
    }

    @Operation(summary = "当前主口径摘要")
    @GetMapping("/main")
    public Map<String, Object> main() {
        CaliberSwitchReview review = arbitrationService.currentMainReview();
        return Map.of(
                "caliberCode", review.getToCaliberCode(),
                "caliberVersion", review.getToCaliberVersion(),
                "status", review.getStatus().name(),
                "effectiveDate", String.valueOf(review.getEffectiveDate()));
    }
}
