package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** 计划时刻版本：航司事后改计划产生新版本，旧版本保留供判定回溯。 */
@Entity
@Table(name = "schedule_version")
public class ScheduleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String flightNo;

    @Column(nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false)
    private int legNo;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant schedDeparture;

    @Column(nullable = false)
    private Instant schedArrival;

    /** 该版本生效时刻；放行判定按业务发生时间匹配当时生效的版本。 */
    @Column(nullable = false)
    private Instant effectiveFrom;

    @Column(nullable = false, length = 32)
    private String source = "SCHEDULE";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ScheduleVersion() {
    }

    public ScheduleVersion(FlightInstanceId instanceId, int version, Instant schedDeparture,
                           Instant schedArrival, Instant effectiveFrom, String source) {
        this.flightNo = instanceId.getFlightNo();
        this.serviceDate = instanceId.getServiceDate();
        this.legNo = instanceId.getLegNo();
        this.version = version;
        this.schedDeparture = schedDeparture;
        this.schedArrival = schedArrival;
        this.effectiveFrom = effectiveFrom;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public Instant getSchedDeparture() {
        return schedDeparture;
    }

    public Instant getSchedArrival() {
        return schedArrival;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getSource() {
        return source;
    }
}
