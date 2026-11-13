package com.example.flightops.service;

import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberStatus;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberDefinitionRepository;
import com.example.flightops.web.dto.NewCaliberVersionRequest;
import com.example.flightops.web.dto.RegisterCaliberRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 口径注册库：保存每套口径的时间戳来源、分母规则与生效版本。
 * 口径定义改动只追加新版本（同 code 下 version 递增，旧版 RETIRED 保留），
 * 物化结果绑定的是具体版本号——事后改口径不会让历史报表跟着变。
 */
@Service
public class CaliberRegistryService {

    private final CaliberDefinitionRepository definitionRepository;

    public CaliberRegistryService(CaliberDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @Transactional(readOnly = true)
    public List<CaliberDefinition> all() {
        return definitionRepository.findAll(org.springframework.data.domain.Sort.by("code", "version"));
    }

    @Transactional(readOnly = true)
    public List<CaliberDefinition> versions(String code) {
        List<CaliberDefinition> versions = definitionRepository.findByCodeOrderByVersionAsc(code);
        if (versions.isEmpty()) {
            throw ApiException.notFound("口径不存在: " + code);
        }
        return versions;
    }

    @Transactional(readOnly = true)
    public CaliberDefinition requireVersion(String code, int version) {
        return definitionRepository.findByCodeAndVersion(code, version)
                .orElseThrow(() -> ApiException.notFound("口径版本不存在: " + code + " v" + version));
    }

    /** 解析口径最新生效版本。 */
    @Transactional(readOnly = true)
    public CaliberDefinition latestActive(String code) {
        CaliberDefinition latest = definitionRepository.findTopByCodeOrderByVersionDesc(code)
                .orElseThrow(() -> ApiException.notFound("口径不存在: " + code));
        if (latest.getStatus() != CaliberStatus.ACTIVE) {
            throw ApiException.conflict("口径 " + code + " 最新版本 v" + latest.getVersion() + " 非 ACTIVE 状态，无法使用");
        }
        return latest;
    }

    /** 当前全部生效口径（每个 code 取最新 ACTIVE 版本）——并行计算的参与方。 */
    @Transactional(readOnly = true)
    public List<CaliberDefinition> activeCalibers() {
        return definitionRepository.findByStatusOrderByCodeAscVersionAsc(CaliberStatus.ACTIVE)
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(CaliberDefinition::getCode,
                        java.util.stream.Collectors.maxBy(
                                java.util.Comparator.comparingInt(CaliberDefinition::getVersion))))
                .values().stream().map(java.util.Optional::orElseThrow).toList();
    }

    /** 注册一套新口径（首版，DRAFT 需显式激活；种子口径已 ACTIVE）。 */
    @Transactional
    public CaliberDefinition register(RegisterCaliberRequest request, String operator) {
        if (definitionRepository.existsByCodeAndVersion(request.code(), 1)) {
            throw ApiException.conflict("口径 code 已存在: " + request.code() + "，改版请走新版本接口");
        }
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now();
        CaliberDefinition v1 = new CaliberDefinition(
                request.code(), request.name(), 1, CaliberStatus.ACTIVE,
                request.timestampSource(), request.denominatorRule(), request.toleranceMinutes(),
                request.description(), "首版发布", effectiveFrom, operator);
        return definitionRepository.save(v1);
    }

    /**
     * 口径改版：生成新版本并退役旧版本。已产出的物化结果仍绑定旧版本号，可按旧版复现；
     * 新计算只绑定新版本——不追溯改写历史。
     */
    @Transactional
    public CaliberDefinition newVersion(String code, NewCaliberVersionRequest request, String operator) {
        List<CaliberDefinition> versions = definitionRepository.findByCodeOrderByVersionAsc(code);
        if (versions.isEmpty()) {
            throw ApiException.notFound("口径不存在: " + code + "，请先注册首版");
        }
        CaliberDefinition previous = versions.stream()
                .max(java.util.Comparator.comparingInt(CaliberDefinition::getVersion)).orElseThrow();
        int nextVersion = previous.getVersion() + 1;
        LocalDate effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now();

        previous.retire();
        definitionRepository.save(previous);

        String name = request.name() != null ? request.name()
                : versions.stream().map(CaliberDefinition::getName).findFirst().orElse(code);
        CaliberDefinition next = new CaliberDefinition(
                code, name, nextVersion, CaliberStatus.ACTIVE,
                request.timestampSource(), request.denominatorRule(), request.toleranceMinutes(),
                request.description(), request.changeSummary(), effectiveFrom, operator);
        return definitionRepository.save(next);
    }
}
