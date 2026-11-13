package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 日报明细（分区表）：每个航班实例一行，日报口径与实时口径分列，
 * 值班经理可指回具体版本和具体航班。
 */
@Entity
@Table(name = "daily_report_item")
public class DailyReportItem {

    @EmbeddedId
    private DailyReportItemId id;

    @Column(nullable = false, length = 16)
    private String finalStatus;

    /** 日报口径：是否计入分母。 */
    @Column(nullable = false)
    private boolean counted;

    /** 日报口径：放行是否正常（未计入分母时为 null）。 */
    private Boolean normal;

    /** 日报口径：延误分钟。 */
    private Integer delayMinutes;

    /** 判定绑定的计划时刻版本。 */
    private Long scheduleVersionId;

    /** 实时口径：重算当时是否计入分母（仅已判定班次计入）。 */
    @Column(nullable = false)
    private boolean realtimeCounted;

    private Boolean realtimeNormal;

    private Integer realtimeDelayMinutes;

    /** 定位：该实例截至本次重算的最大变更版本。 */
    @Column(nullable = false)
    private int lastChangeVersion;

    protected DailyReportItem() {
    }

    public DailyReportItem(DailyReportItemId id, String finalStatus,
                           boolean counted, Boolean normal, Integer delayMinutes, Long scheduleVersionId,
                           boolean realtimeCounted, Boolean realtimeNormal, Integer realtimeDelayMinutes,
                           int lastChangeVersion) {
        this.id = id;
        this.finalStatus = finalStatus;
        this.counted = counted;
        this.normal = normal;
        this.delayMinutes = delayMinutes;
        this.scheduleVersionId = scheduleVersionId;
        this.realtimeCounted = realtimeCounted;
        this.realtimeNormal = realtimeNormal;
        this.realtimeDelayMinutes = realtimeDelayMinutes;
        this.lastChangeVersion = lastChangeVersion;
    }

    public DailyReportItemId getId() {
        return id;
    }

    public String getFinalStatus() {
        return finalStatus;
    }

    public boolean isCounted() {
        return counted;
    }

    public Boolean getNormal() {
        return normal;
    }

    public Integer getDelayMinutes() {
        return delayMinutes;
    }

    public Long getScheduleVersionId() {
        return scheduleVersionId;
    }

    public boolean isRealtimeCounted() {
        return realtimeCounted;
    }

    public Boolean getRealtimeNormal() {
        return realtimeNormal;
    }

    public Integer getRealtimeDelayMinutes() {
        return realtimeDelayMinutes;
    }

    public int getLastChangeVersion() {
        return lastChangeVersion;
    }
}
