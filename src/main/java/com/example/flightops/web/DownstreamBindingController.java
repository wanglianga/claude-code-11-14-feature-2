package com.example.flightops.web;

import com.example.flightops.domain.DownstreamBinding;
import com.example.flightops.domain.DownstreamTarget;
import com.example.flightops.service.DownstreamBindingService;
import com.example.flightops.web.dto.BindingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import java.util.List;

/**
 * 下游绑定台账：大屏、月报、对外报送各引用哪一版（口径版本 + 物化结果双绑定）。
 */
@Tag(name = "多口径-下游绑定台账", description = "大屏/月报/对外报送引用版本的绑定与审计")
@RestController
@RequestMapping("/api/downstream-bindings")
public class DownstreamBindingController {

    private final DownstreamBindingService bindingService;

    public DownstreamBindingController(DownstreamBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @Operation(summary = "登记绑定：消费方引用某口径版本（月报/报送必须绑定具体物化运行）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DownstreamBinding bind(@Valid @RequestBody BindingRequest request, Principal principal) {
        return bindingService.bind(request, principal != null ? principal.getName() : "manager");
    }

    @Operation(summary = "某消费方的绑定历史")
    @GetMapping("/{target}")
    public List<DownstreamBinding> history(@PathVariable DownstreamTarget target) {
        return bindingService.history(target);
    }

    @Operation(summary = "全部生效绑定（当前大屏/月报/报送各引用哪一版）")
    @GetMapping("/active")
    public List<DownstreamBinding> active() {
        return bindingService.activeBindings();
    }

    @Operation(summary = "按口径版本反查：该版本被哪些下游引用（切换影响面评估）")
    @GetMapping("/by-caliber")
    public List<DownstreamBinding> byCaliber(@RequestParam String code, @RequestParam int version) {
        return bindingService.byCaliberVersion(code, version);
    }
}
