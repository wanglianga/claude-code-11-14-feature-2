package com.example.flightops.service;

import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberReleaseDiff;
import com.example.flightops.domain.CaliberResultItem;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.CaliberRunStatus;
import com.example.flightops.domain.CaliberSwitchConflict;
import com.example.flightops.domain.CaliberSwitchReview;
import com.example.flightops.domain.DailyReport;
import com.example.flightops.domain.DiffAttribution;
import com.example.flightops.domain.ReviewStatus;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberReleaseDiffRepository;
import com.example.flightops.repo.CaliberResultItemRepository;
import com.example.flightops.repo.CaliberRunRepository;
import com.example.flightops.repo.CaliberSwitchConflictRepository;
import com.example.flightops.repo.CaliberSwitchReviewRepository;
import com.example.flightops.repo.ChangeLogRepository;
import com.example.flightops.repo.DailyReportRepository;
import com.example.flightops.web.dto.ReviewDecisionRequest;
import com.example.flightops.web.dto.SwitchReviewRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 发布仲裁：
 * 1) 发布前逐航班比对新旧两套结果，每条差额归因到具体状态变更；无法解释的差额拦截发布；
 * 2) 主口径切换走评审，评审撞上日报锁定时锁定优先，切换顺延到下一发布周期并留冲突记录。
 */
@Service
public class CaliberReleaseArbitrationService {

    private final CaliberRunRepository runRepository;
    private final CaliberResultItemRepository itemRepository;
    private final CaliberReleaseDiffRepository diffRepository;
    private final CaliberSwitchReviewRepository reviewRepository;
    private final CaliberSwitchConflictRepository conflictRepository;
    private final DailyReportRepository dailyReportRepository;
    private final ChangeLogRepository changeLogRepository;
    private final CaliberRegistryService registryService;
    private final TransactionTemplate requiresNew;

    public CaliberReleaseArbitrationService(CaliberRunRepository runRepository,
                                            CaliberResultItemRepository itemRepository,
                                            CaliberReleaseDiffRepository diffRepository,
                                            CaliberSwitchReviewRepository reviewRepository,
                                            CaliberSwitchConflictRepository conflictRepository,
                                            DailyReportRepository dailyReportRepository,
                                            ChangeLogRepository changeLogRepository,
                                            CaliberRegistryService registryService,
                                            org.springframework.transaction.PlatformTransactionManager txManager) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.diffRepository = diffRepository;
        this.reviewRepository = reviewRepository;
        this.conflictRepository = conflictRepository;
        this.dailyReportRepository = dailyReportRepository;
        this.changeLogRepository = changeLogRepository;
        this.registryService = registryService;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public record PublishOutcome(CaliberRun run, List<CaliberReleaseDiff> diffs, boolean blocked,
                                 List<String> unexplainedKeys) {
    }

    /**
     * 发布一份物化运行：与该口径当期已发布运行逐航班比对。
     * 每条差额必须能归因到两版之间该实例的状态变更；出现不可解释差额 → 运行置 BLOCKED 并拒绝发布。
     */
    @Transactional
    public PublishOutcome publish(LocalDate date, Long runId) {
        CaliberRun candidate = runRepository.findById(new com.example.flightops.domain.CaliberRunId(runId, date))
                .orElseThrow(() -> ApiException.notFound("物化运行不存在: runId=" + runId + " date=" + date));
        if (candidate.getStatus() == CaliberRunStatus.PUBLISHED) {
            throw ApiException.conflict("运行 " + runId + " 已发布，无需重复发布");
        }

        Optional<CaliberRun> baseOpt = runRepository.findTopByServiceDateAndCaliberCodeAndStatusOrderByRunNoDesc(
                date, candidate.getCaliberCode(), CaliberRunStatus.PUBLISHED);
        List<CaliberReleaseDiff> diffs = new ArrayList<>();
        List<String> unexplained = new ArrayList<>();

        if (baseOpt.isPresent()) {
            CaliberRun base = baseOpt.get();
            // 口径定义改版造成的差额归因于口径版本切换；同口径版本下的差额必须能指到具体状态变更
            boolean caliberChanged = candidate.getCaliberVersion() != base.getCaliberVersion();
            String caliberAttribution = caliberChanged
                    ? "CALIBER_DEF_V" + base.getCaliberVersion() + "->V" + candidate.getCaliberVersion()
                    : null;
            Map<String, CaliberResultItem> baseItems = itemRepository
                    .findByIdRunIdOrderByIdFlightNoAscIdLegNoAsc(base.getId()).stream()
                    .collect(Collectors.toMap(i -> key(i.getId().getFlightNo(), i.getId().getLegNo()), i -> i));
            List<CaliberResultItem> newItems =
                    itemRepository.findByIdRunIdOrderByIdFlightNoAscIdLegNoAsc(candidate.getId());

            for (CaliberResultItem next : newItems) {
                String flightNo = next.getId().getFlightNo();
                int legNo = next.getId().getLegNo();
                CaliberResultItem prev = baseItems.get(key(flightNo, legNo));
                int baseChangeVersion = prev == null ? 0 : prev.getLastChangeVersion();
                String causedBy = caliberChanged ? caliberAttribution
                        : causedByChanges(date, flightNo, legNo, baseChangeVersion, next.getLastChangeVersion());
                diffs.addAll(compareItem(date, candidate, base.getId(), next, prev, causedBy, unexplained));
            }
        }

        if (!unexplained.isEmpty()) {
            // 拦截结论与差额留痕必须在独立事务提交：外层事务随异常回滚，审计证据不能丢
            requiresNew.executeWithoutResult(status -> {
                diffRepository.saveAll(diffs);
                candidate.markBlocked();
                runRepository.save(candidate);
            });
            throw ApiException.conflict("发布被拦截：存在 " + unexplained.size()
                    + " 条无法归因到具体状态变更的差额: " + String.join("; ", unexplained)
                    + "（差额明细见 caliber_release_diff，attribution=UNEXPLAINED）");
        }

        diffRepository.saveAll(diffs);
        baseOpt.ifPresent(base -> {
            base.markSuperseded();
            runRepository.save(base);
        });
        candidate.markPublished();
        return new PublishOutcome(runRepository.save(candidate), diffs, false, List.of());
    }

    /** 发起主口径切换评审。 */
    @Transactional
    public CaliberSwitchReview proposeSwitch(SwitchReviewRequest request, String operator) {
        CaliberDefinition target = request.toCaliberVersion() != null
                ? registryService.requireVersion(request.toCaliberCode(), request.toCaliberVersion())
                : registryService.latestActive(request.toCaliberCode());
        CaliberSwitchReview current = currentMainReview();
        String fromCode = current.getToCaliberCode();
        if (fromCode.equals(target.getCode()) && current.getToCaliberVersion() == target.getVersion()) {
            throw ApiException.conflict("目标口径 " + target.getCode() + " v" + target.getVersion()
                    + " 已是当前主口径，无需切换");
        }
        return reviewRepository.save(new CaliberSwitchReview(
                request.serviceDate(), fromCode, target.getCode(), target.getVersion(),
                ReviewStatus.PROPOSED, request.reason(), operator));
    }

    /**
     * 评审决定：批准时检查目标周期的日报锁定——
     * 锁定优先：评审置 CONFLICT_DEFERRED、顺延到下一发布周期并写冲突记录；否则立即生效为主口径。
     */
    @Transactional
    public CaliberSwitchReview decide(Long reviewId, ReviewDecisionRequest request, String operator) {
        CaliberSwitchReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("切换评审不存在: id=" + reviewId));
        if (review.getStatus() != ReviewStatus.PROPOSED) {
            throw ApiException.conflict("评审 id=" + reviewId + " 当前状态 " + review.getStatus()
                    + "，仅 PROPOSED 可决定");
        }
        if (!request.approved()) {
            review.decide(ReviewStatus.REJECTED, operator,
                    request.comment() != null ? request.comment() : "评审驳回", review.getServiceDate());
            return reviewRepository.save(review);
        }

        List<DailyReport> locked = dailyReportRepository
                .findByReportDateAndStatus(review.getServiceDate(), DailyReport.Status.LOCKED.name());
        if (!locked.isEmpty()) {
            LocalDate deferredTo = review.getServiceDate().plusDays(1);
            review.decide(ReviewStatus.CONFLICT_DEFERRED, operator,
                    "目标周期日报已锁定，锁定优先，切换顺延至 " + deferredTo
                            + (request.comment() != null ? "；" + request.comment() : ""),
                    deferredTo);
            review = reviewRepository.save(review);
            DailyReport lockedReport = locked.get(0);
            conflictRepository.save(new CaliberSwitchConflict(
                    review.getId(), review.getServiceDate(), lockedReport.getId(),
                    lockedReport.getLockedAt() != null ? lockedReport.getLockedAt() : java.time.Instant.now(),
                    deferredTo, "切换评审撞上日报锁定（reportId=" + lockedReport.getId() + "），锁定优先，切换顺延"));
            return review;
        }

        review.decide(ReviewStatus.APPLIED, operator,
                request.comment() != null ? request.comment() : "评审通过并生效", review.getServiceDate());
        return reviewRepository.save(review);
    }

    /** 顺延到期后重新落地：目标周期无锁定则生效，并把冲突记录标记为已解决。 */
    @Transactional
    public CaliberSwitchReview resolveDeferred(Long reviewId, String operator) {
        CaliberSwitchReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("切换评审不存在: id=" + reviewId));
        if (review.getStatus() != ReviewStatus.CONFLICT_DEFERRED) {
            throw ApiException.conflict("评审 id=" + reviewId + " 非 CONFLICT_DEFERRED 状态，无法落地顺延切换");
        }
        LocalDate effectiveDate = review.getEffectiveDate();
        List<DailyReport> locked =
                dailyReportRepository.findByReportDateAndStatus(effectiveDate, DailyReport.Status.LOCKED.name());
        if (!locked.isEmpty()) {
            throw ApiException.conflict("顺延周期 " + effectiveDate + " 日报仍锁定，切换继续等待（冲突记录保留）");
        }
        review.markApplied(effectiveDate);
        review = reviewRepository.save(review);
        conflictRepository.findByServiceDateOrderByIdAsc(review.getServiceDate()).stream()
                .filter(c -> c.getReviewId().equals(reviewId) && !c.isResolved())
                .forEach(c -> {
                    c.resolve();
                    conflictRepository.save(c);
                });
        return review;
    }

    @Transactional(readOnly = true)
    public CaliberSwitchReview currentMainReview() {
        return reviewRepository.findTopByStatusOrderByAppliedAtDescIdDesc(ReviewStatus.APPLIED)
                .orElseThrow(() -> ApiException.conflict("无已生效的主口径切换记录"));
    }

    @Transactional(readOnly = true)
    public List<CaliberSwitchReview> reviews(LocalDate date) {
        return reviewRepository.findByServiceDateOrderByIdAsc(date);
    }

    @Transactional(readOnly = true)
    public List<CaliberSwitchReview> allReviews() {
        return reviewRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<CaliberSwitchConflict> conflicts(LocalDate date) {
        return conflictRepository.findByServiceDateOrderByIdAsc(date);
    }

    @Transactional(readOnly = true)
    public List<CaliberReleaseDiff> releaseDiffs(LocalDate date, Long runId) {
        return diffRepository.findByIdServiceDateAndIdPublishRunIdOrderByIdFlightNoAscIdLegNoAscIdFieldAsc(
                date, runId);
    }

    /** 逐航班字段比对：counted / normal / delay_minutes。 */
    private List<CaliberReleaseDiff> compareItem(LocalDate date, CaliberRun candidate, Long baseRunId,
                                                 CaliberResultItem next, CaliberResultItem prev,
                                                 String causedBy, List<String> unexplained) {
        List<CaliberReleaseDiff> out = new ArrayList<>();
        String flightNo = next.getId().getFlightNo();
        int legNo = next.getId().getLegNo();

        boolean prevCounted = prev != null && prev.isCounted();
        if (prevCounted != next.isCounted()) {
            out.add(buildDiff(date, candidate, baseRunId, flightNo, legNo, "counted",
                    String.valueOf(prevCounted), String.valueOf(next.isCounted()), causedBy, unexplained));
        }
        if (!Objects.equals(prev == null ? null : prev.getNormal(), next.getNormal())) {
            out.add(buildDiff(date, candidate, baseRunId, flightNo, legNo, "normal",
                    prev == null ? null : String.valueOf(prev.getNormal()),
                    String.valueOf(next.getNormal()), causedBy, unexplained));
        }
        Integer prevDelay = prev == null ? null : prev.getDelayMinutes();
        if (!Objects.equals(prevDelay, next.getDelayMinutes())) {
            out.add(buildDiff(date, candidate, baseRunId, flightNo, legNo, "delay_minutes",
                    prevDelay == null ? null : String.valueOf(prevDelay),
                    next.getDelayMinutes() == null ? null : String.valueOf(next.getDelayMinutes()),
                    causedBy, unexplained));
        }
        return out;
    }

    /** 归因：两版之间该实例存在状态变更 → EXPLAINED；无任何变更却产生差额 → UNEXPLAINED，拦截发布。 */
    private CaliberReleaseDiff buildDiff(LocalDate date, CaliberRun candidate, Long baseRunId,
                                         String flightNo, int legNo, String field,
                                         String oldValue, String newValue,
                                         String causedBy, List<String> unexplained) {
        DiffAttribution attribution = causedBy.isBlank() ? DiffAttribution.UNEXPLAINED : DiffAttribution.EXPLAINED;
        if (attribution == DiffAttribution.UNEXPLAINED) {
            unexplained.add(flightNo + "/L" + legNo + "." + field + " " + oldValue + "->" + newValue);
        }
        return new CaliberReleaseDiff(
                new CaliberReleaseDiff.Id(date, candidate.getId(), flightNo, legNo, field),
                candidate.getCaliberCode(), baseRunId, oldValue, newValue, attribution,
                causedBy.isBlank() ? null : causedBy);
    }

    /** 归因到具体变更：两版之间该实例发生的变更版本号列表。 */
    private String causedByChanges(LocalDate date, String flightNo, int legNo, int fromVersion, int toVersion) {
        if (toVersion <= fromVersion) {
            return "";
        }
        return changeLogRepository.findInVersionRange(flightNo, date, legNo, fromVersion, toVersion)
                .stream().map(e -> String.valueOf(e.getId().getChangeVersion()))
                .collect(Collectors.joining(","));
    }

    private static String key(String flightNo, int legNo) {
        return flightNo + "#" + legNo;
    }
}
