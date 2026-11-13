package com.example.flightops.service;

import com.example.flightops.domain.CaliberCompensation;
import com.example.flightops.domain.CaliberResultItem;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberCompensationRepository;
import com.example.flightops.repo.CaliberResultItemRepository;
import com.example.flightops.repo.CaliberRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多口径查询：并行结果清单、单运行明细、同航班跨口径对比（机场正常/航司延误的常态可逐行验证）、
 * 补偿留痕审计（校验每个口径都被补偿，没有分区静默分叉）。
 */
@Service
public class CaliberQueryService {

    private final CaliberRunRepository runRepository;
    private final CaliberResultItemRepository itemRepository;
    private final CaliberCompensationRepository compensationRepository;

    public CaliberQueryService(CaliberRunRepository runRepository,
                               CaliberResultItemRepository itemRepository,
                               CaliberCompensationRepository compensationRepository) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.compensationRepository = compensationRepository;
    }

    @Transactional(readOnly = true)
    public List<CaliberRun> runs(LocalDate date) {
        return runRepository.findByServiceDateOrderByCaliberCodeAscRunNoAsc(date);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> runDetail(LocalDate date, Long runId) {
        CaliberRun run = runRepository.findById(new com.example.flightops.domain.CaliberRunId(runId, date))
                .orElseThrow(() -> ApiException.notFound("物化运行不存在: runId=" + runId + " date=" + date));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("items", itemRepository.findByIdRunIdOrderByIdFlightNoAscIdLegNoAsc(runId));
        return result;
    }

    /**
     * 同航班跨口径对比：返回该航班在每个口径最新运行中的物化结论。
     * 两侧时间戳来源不同（推出 vs 起飞），机场判正常、航司判延误在此一目了然。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> crossCaliberFlight(LocalDate date, String flightNo, int legNo) {
        List<CaliberResultItem> items = itemRepository
                .findByIdServiceDateAndIdFlightNoAndIdLegNoOrderByIdRunIdAsc(date, flightNo, legNo);
        Map<Long, CaliberRun> runsById = new LinkedHashMap<>();
        for (CaliberRun run : runRepository.findByServiceDateOrderByCaliberCodeAscRunNoAsc(date)) {
            runsById.put(run.getId(), run);
        }
        List<Map<String, Object>> compared = new java.util.ArrayList<>();
        for (CaliberResultItem item : items) {
            CaliberRun run = runsById.get(item.getId().getRunId());
            if (run == null) {
                continue;
            }
            // 只保留每口径最新运行，避免被补偿取代的旧运行干扰对比
            if (runRepository.findTopByServiceDateAndCaliberCodeOrderByRunNoDesc(date, run.getCaliberCode())
                    .map(CaliberRun::getId).orElse(-1L) != run.getId()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caliberCode", run.getCaliberCode());
            row.put("caliberVersion", run.getCaliberVersion());
            row.put("runId", run.getId());
            row.put("runNo", run.getRunNo());
            row.put("timestampSource", run.getTimestampSource());
            row.put("denominatorRule", run.getDenominatorRule());
            row.put("counted", item.isCounted());
            row.put("normal", item.getNormal());
            row.put("delayMinutes", item.getDelayMinutes());
            row.put("referenceTime", item.getReferenceTime());
            row.put("backfillScope", item.getBackfillScope());
            row.put("lastChangeVersion", item.getLastChangeVersion());
            compared.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceDate", date.toString());
        result.put("flightNo", flightNo);
        result.put("legNo", legNo);
        result.put("caliberResults", compared);
        return result;
    }

    @Transactional(readOnly = true)
    public List<CaliberCompensation> compensations(LocalDate date) {
        return compensationRepository.findByServiceDateOrderByIdAsc(date);
    }

    @Transactional(readOnly = true)
    public List<CaliberCompensation> compensationsForFlight(LocalDate date, String flightNo, int legNo) {
        return compensationRepository.findByServiceDateAndFlightNoAndLegNoOrderByIdAsc(
                date, flightNo, legNo);
    }

    /**
     * 分区分叉审计：按触发事件分组，检查每次补偿是否覆盖全部生效口径。
     * 返回缺失口径的触发组（空列表 = 无分叉）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forkAudit(LocalDate date) {
        List<CaliberCompensation> all = compensationRepository.findByServiceDateOrderByIdAsc(date);
        Map<String, List<CaliberCompensation>> groups = new LinkedHashMap<>();
        for (CaliberCompensation c : all) {
            String groupKey = c.getFlightNo() + "/L" + c.getLegNo()
                    + "#" + c.getTriggerChangeVersions();
            groups.computeIfAbsent(groupKey, k -> new java.util.ArrayList<>()).add(c);
        }
        List<Map<String, Object>> missing = new java.util.ArrayList<>();
        for (Map.Entry<String, List<CaliberCompensation>> entry : groups.entrySet()) {
            List<String> covered = entry.getValue().stream().map(CaliberCompensation::getCaliberCode).distinct().toList();
            List<String> expected = runRepository.findByServiceDateOrderByCaliberCodeAscRunNoAsc(date).stream()
                    .map(CaliberRun::getCaliberCode).distinct().toList();
            List<String> absent = expected.stream().filter(code -> !covered.contains(code)).toList();
            if (!absent.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trigger", entry.getKey());
                row.put("coveredCalibers", covered);
                row.put("missingCalibers", absent);
                missing.add(row);
            }
        }
        return missing;
    }
}
