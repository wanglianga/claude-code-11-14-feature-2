package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** 告警：非法状态流转被拒绝时记录（错误状态比缺状态更难查，必须显式暴露）。 */
@Entity
@Table(name = "alert")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String flightNo;

    @Column(nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false)
    private int legNo;

    @Column(nullable = false, length = 64)
    private String rule;

    @Column(length = 1024)
    private String message;

    @Column(nullable = false)
    private boolean acknowledged = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Alert() {
    }

    public Alert(FlightInstanceId instanceId, String rule, String message) {
        this.flightNo = instanceId.getFlightNo();
        this.serviceDate = instanceId.getServiceDate();
        this.legNo = instanceId.getLegNo();
        this.rule = rule;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public int getLegNo() {
        return legNo;
    }

    public String getRule() {
        return rule;
    }

    public String getMessage() {
        return message;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
