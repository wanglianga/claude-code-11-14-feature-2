package com.example.flightops.service;

import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.CaliberRunId;
import com.example.flightops.domain.DownstreamBinding;
import com.example.flightops.domain.DownstreamTarget;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.CaliberRunRepository;
import com.example.flightops.repo.DownstreamBindingRepository;
import com.example.flightops.web.dto.BindingRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 下游绑定台账：记录大屏、月报、对外报送各引用哪一版（口径定义版本 + 物化结果双绑定）。
 * 绑定一旦形成不因口径切换而改写；月报/报送锁定后仍按当时绑定版本复现。
 */
@Service
public class DownstreamBindingService {

    private final DownstreamBindingRepository bindingRepository;
    private final CaliberRunRepository runRepository;
    private final CaliberRegistryService registryService;

    public DownstreamBindingService(DownstreamBindingRepository bindingRepository,
                                    CaliberRunRepository runRepository,
                                    CaliberRegistryService registryService) {
        this.bindingRepository = bindingRepository;
        this.runRepository = runRepository;
        this.registryService = registryService;
    }

    @Transactional
    public DownstreamBinding bind(BindingRequest request, String operator) {
        CaliberDefinition caliber = request.caliberVersion() != null
                ? registryService.requireVersion(request.caliberCode(), request.caliberVersion())
                : registryService.latestActive(request.caliberCode());

        if (request.runId() != null) {
            if (request.serviceDate() == null) {
                throw ApiException.badRequest("绑定具体物化结果 runId 时必须同时提供 serviceDate");
            }
            CaliberRun run = runRepository.findById(
                            new CaliberRunId(request.runId(), request.serviceDate()))
                    .orElseThrow(() -> ApiException.notFound(
                            "物化运行不存在: runId=" + request.runId() + " date=" + request.serviceDate()));
            if (!run.getCaliberCode().equals(caliber.getCode())
                    || run.getCaliberVersion() != caliber.getVersion()) {
                throw ApiException.conflict("物化结果属于口径 " + run.getCaliberCode() + " v"
                        + run.getCaliberVersion() + "，与绑定口径 " + caliber.getCode()
                        + " v" + caliber.getVersion() + " 不一致");
            }
        } else if (request.target() != DownstreamTarget.SCREEN) {
            throw ApiException.badRequest(request.target() + " 必须绑定具体物化结果 runId（大屏可只绑口径版本）");
        }

        // 同一消费方同一周期的旧绑定失效，台账保留历史行
        if (request.period() != null) {
            bindingRepository.findFirstByTargetAndPeriodAndActiveTrueOrderByIdDesc(request.target(), request.period())
                    .ifPresent(this::deactivate);
        } else {
            bindingRepository.findFirstByTargetAndActiveTrueOrderByIdDesc(request.target())
                    .ifPresent(this::deactivate);
        }

        return bindingRepository.save(new DownstreamBinding(
                request.target(), request.period(), caliber.getCode(), caliber.getVersion(),
                request.runId(), request.serviceDate(), operator, request.note()));
    }

    @Transactional(readOnly = true)
    public List<DownstreamBinding> history(DownstreamTarget target) {
        return bindingRepository.findByTargetOrderByIdDesc(target);
    }

    @Transactional(readOnly = true)
    public List<DownstreamBinding> activeBindings() {
        return bindingRepository.findByActiveTrueOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<DownstreamBinding> byCaliberVersion(String caliberCode, int version) {
        return bindingRepository.findByCaliberCodeAndCaliberVersionOrderByIdDesc(caliberCode, version);
    }

    private void deactivate(DownstreamBinding binding) {
        binding.deactivate();
        bindingRepository.save(binding);
    }
}
