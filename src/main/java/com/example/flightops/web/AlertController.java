package com.example.flightops.web;

import com.example.flightops.domain.Alert;
import com.example.flightops.error.ApiException;
import com.example.flightops.repo.AlertRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "告警", description = "非法状态流转等告警（错误状态显式暴露）")
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository alertRepository;

    public AlertController(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Operation(summary = "查询告警（按日期或未确认）")
    @GetMapping
    public List<Alert> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "false") boolean unacknowledgedOnly) {
        if (unacknowledgedOnly) {
            return alertRepository.findByAcknowledgedFalseOrderByCreatedAtDesc();
        }
        if (date != null) {
            return alertRepository.findByServiceDateOrderByCreatedAtDesc(date);
        }
        return alertRepository.findAll();
    }

    @Operation(summary = "确认告警")
    @PutMapping("/{id}/acknowledge")
    public Alert acknowledge(@PathVariable long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("告警不存在: " + id));
        alert.setAcknowledged(true);
        return alertRepository.save(alert);
    }
}
