package com.example.flightops.web;

import com.example.flightops.service.EventIngestService;
import com.example.flightops.web.dto.IngestEventRequest;
import com.example.flightops.web.dto.IngestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "事件摄入", description = "状态变更事件：状态机校验、迟到窗口、正负号变更日志")
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventIngestService ingestService;

    public EventController(EventIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Operation(summary = "上报状态变更事件（非法流转拒绝并告警；超窗走补录）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResult ingest(@Valid @RequestBody IngestEventRequest request) {
        return ingestService.ingest(request);
    }
}
