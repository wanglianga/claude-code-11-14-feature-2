package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 航班实例：同一航班号同一执行日期不同航段各自成实例；补班、加班为独立实例。
 * 当前状态只是投影，事实以变更日志为准。
 */
@Entity
@Table(name = "flight_instance")
public class FlightInstance {

    @EmbeddedId
    private FlightInstanceId id;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstanceKind instanceKind = InstanceKind.REGULAR;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FlightStatus currentStatus = FlightStatus.PLANNED;

    /** 实例内变更序号，与变更日志 change_version 对应。 */
    @Column(nullable = false)
    private int currentVersion = 0;

    /** 放行判定绑定的计划时刻版本（判定时生效版本，事后改计划不回写）。 */
    private Long releaseVersionId;

    private Boolean releaseNormal;

    private Integer releaseDelayMinutes;

    private Instant releaseJudgedAt;

    /** 首次实际起飞时间（放行判定基准）。 */
    private Instant firstDepartureTime;

    private Instant actualArrivalTime;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    protected FlightInstance() {
    }

    public FlightInstance(FlightInstanceId id, String origin, String destination, InstanceKind kind) {
        this.id = id;
        this.origin = origin;
        this.destination = destination;
        this.instanceKind = kind;
    }

    public FlightInstanceId getId() {
        return id;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public InstanceKind getInstanceKind() {
        return instanceKind;
    }

    public FlightStatus getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(FlightStatus currentStatus) {
        this.currentStatus = currentStatus;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Long getReleaseVersionId() {
        return releaseVersionId;
    }

    public void setReleaseVersionId(Long releaseVersionId) {
        this.releaseVersionId = releaseVersionId;
    }

    public Boolean getReleaseNormal() {
        return releaseNormal;
    }

    public void setReleaseNormal(Boolean releaseNormal) {
        this.releaseNormal = releaseNormal;
    }

    public Integer getReleaseDelayMinutes() {
        return releaseDelayMinutes;
    }

    public void setReleaseDelayMinutes(Integer releaseDelayMinutes) {
        this.releaseDelayMinutes = releaseDelayMinutes;
    }

    public Instant getReleaseJudgedAt() {
        return releaseJudgedAt;
    }

    public void setReleaseJudgedAt(Instant releaseJudgedAt) {
        this.releaseJudgedAt = releaseJudgedAt;
    }

    public Instant getFirstDepartureTime() {
        return firstDepartureTime;
    }

    public void setFirstDepartureTime(Instant firstDepartureTime) {
        this.firstDepartureTime = firstDepartureTime;
    }

    public Instant getActualArrivalTime() {
        return actualArrivalTime;
    }

    public void setActualArrivalTime(Instant actualArrivalTime) {
        this.actualArrivalTime = actualArrivalTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDate getServiceDate() {
        return id.getServiceDate();
    }
}
