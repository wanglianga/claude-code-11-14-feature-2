package com.example.flightops.web;

import com.example.flightops.service.CaliberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "统计口径", description = "固化的统计口径定义")
@RestController
@RequestMapping("/api/caliber")
public class CaliberController {

    private final CaliberService caliberService;

    public CaliberController(CaliberService caliberService) {
        this.caliberService = caliberService;
    }

    @Operation(summary = "当前生效的统计口径（写死，含取消/备降/恢复等边界规则）")
    @GetMapping
    public Map<String, Object> caliber() {
        return caliberService.caliber();
    }
}
