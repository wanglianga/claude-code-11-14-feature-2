package com.example.flightops.service;

import com.example.flightops.domain.Alert;
import com.example.flightops.domain.FlightInstanceId;
import com.example.flightops.repo.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 告警服务：独立事务写入，非法流转被拒绝回滚时告警仍然保留。 */
@Service
public class AlertService {

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Alert raise(FlightInstanceId instanceId, String rule, String message) {
        return alertRepository.save(new Alert(instanceId, rule, message));
    }
}
