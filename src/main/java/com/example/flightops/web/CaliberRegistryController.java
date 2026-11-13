package com.example.flightops.web;

import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberSwitchReview;
import com.example.flightops.service.CaliberRegistryService;
import com.example.flightops.service.CaliberReleaseArbitrationService;
import com.example.flightops.web.dto.NewCaliberVersionRequest;
import com.example.flightops.web.dto.RegisterCaliberRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 口径注册库：每套口径的时间戳来源、分母规则与生效版本。
 * 改口径只生成新版本，旧版保留供已锁定月报按绑定版本复现。
 */
@Tag(name = "多口径-口径注册库", description = "口径定义版本化：时间戳来源、分母规则、生效版本")
@RestController
@RequestMapping("/api/calibers")
public class CaliberRegistryController {

    private final CaliberRegistryService registryService;
    private final CaliberReleaseArbitrationService arbitrationService;

    public CaliberRegistryController(CaliberRegistryService registryService,
                                     CaliberReleaseArbitrationService arbitrationService) {
        this.registryService = registryService;
        this.arbitrationService = arbitrationService;
    }

    @Operation(summary = "全部口径定义（含历史版本，旧版 RETIRED 保留）")
    @GetMapping
    public List<CaliberDefinition> all() {
        return registryService.all();
    }

    @Operation(summary = "当前主口径（最近一次 APPLIED 切换评审指向的口径版本）")
    @GetMapping("/main")
    public Map<String, Object> main() {
        CaliberSwitchReview review = arbitrationService.currentMainReview();
        CaliberDefinition definition = registryService.requireVersion(review.getToCaliberCode(),
                review.getToCaliberVersion());
        return Map.of(
                "caliberCode", review.getToCaliberCode(),
                "caliberVersion", review.getToCaliberVersion(),
                "effectiveDate", String.valueOf(review.getEffectiveDate()),
                "appliedAt", String.valueOf(review.getAppliedAt()),
                "definition", definition);
    }

    @Operation(summary = "某口径的全部版本")
    @GetMapping("/{code}")
    public List<CaliberDefinition> versions(@PathVariable String code) {
        return registryService.versions(code);
    }

    @Operation(summary = "某口径指定版本定义（历史复现入口）")
    @GetMapping("/{code}/versions/{version}")
    public CaliberDefinition version(@PathVariable String code, @PathVariable int version) {
        return registryService.requireVersion(code, version);
    }

    @Operation(summary = "注册一套新口径（首版）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaliberDefinition register(@Valid @RequestBody RegisterCaliberRequest request, Principal principal) {
        return registryService.register(request, principal != null ? principal.getName() : "manager");
    }

    @Operation(summary = "口径改版：生成新版本并退役旧版，历史物化结果不追溯改写")
    @PostMapping("/{code}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public CaliberDefinition newVersion(@PathVariable String code,
                                        @Valid @RequestBody NewCaliberVersionRequest request,
                                        Principal principal) {
        return registryService.newVersion(code, request, principal != null ? principal.getName() : "manager");
    }
}
