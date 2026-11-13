package com.example.flightops.service;

import com.example.flightops.domain.BackfillScope;
import com.example.flightops.domain.CaliberBackfillSegment;
import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberBackfillSegmentRepository;
import com.example.flightops.web.dto.BackfillRangeRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史补齐：按新口径对历史区间补算。补齐产生的运行与明细一律标 BACKFILL，
 * 与原始区间（ORIGINAL）分列；区间、口径版本、原因写入 caliber_backfill_segment，
 * 配合结果明细的 backfill_scope 可逐条查到受影响航班。
 */
@Service
public class CaliberBackfillService {

    private final CaliberRegistryService registryService;
    private final CaliberComputeEngine computeEngine;
    private final CaliberBackfillSegmentRepository segmentRepository;

    public CaliberBackfillService(CaliberRegistryService registryService,
                                  CaliberComputeEngine computeEngine,
                                  CaliberBackfillSegmentRepository segmentRepository) {
        this.registryService = registryService;
        this.computeEngine = computeEngine;
        this.segmentRepository = segmentRepository;
    }

    public record BackfillOutcome(List<CaliberBackfillSegment> segments, List<CaliberRun> runs) {
    }

    @Transactional
    public BackfillOutcome backfillRange(BackfillRangeRequest request, String operator) {
        if (request.rangeTo().isBefore(request.rangeFrom())) {
            throw ApiException.badRequest("补齐区间 rangeTo 不能早于 rangeFrom");
        }
        List<CaliberDefinition> calibers = request.caliberCode() == null || request.caliberCode().isBlank()
                ? registryService.activeCalibers()
                : List.of(registryService.latestActive(request.caliberCode()));

        List<CaliberRun> runs = new ArrayList<>();
        List<CaliberBackfillSegment> segments = new ArrayList<>();
        for (CaliberDefinition caliber : calibers) {
            for (LocalDate date = request.rangeFrom(); !date.isAfter(request.rangeTo()); date = date.plusDays(1)) {
                runs.add(computeEngine.computeOne(date, caliber, BackfillScope.BACKFILL,
                        "历史补齐: " + request.reason(), null));
            }
            segments.add(segmentRepository.save(new CaliberBackfillSegment(
                    caliber.getCode(), caliber.getVersion(), request.rangeFrom(), request.rangeTo(),
                    BackfillScope.BACKFILL, request.reason(), operator)));
        }
        return new BackfillOutcome(segments, runs);
    }

    @Transactional(readOnly = true)
    public List<CaliberBackfillSegment> segments(String caliberCode) {
        if (caliberCode == null || caliberCode.isBlank()) {
            return segmentRepository.findAllByOrderByRangeFromAscIdAsc();
        }
        return segmentRepository.findByCaliberCodeOrderByRangeFromAscIdAsc(caliberCode);
    }
}
