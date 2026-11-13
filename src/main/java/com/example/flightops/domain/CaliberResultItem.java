package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 口径结果明细：每口径每航班一行的物化结果。
 * reference_time 为该口径时间戳来源对应的事实时间（推出/起飞/到达），
 * 同航班机场判正常、航司判延误可逐行对比，差额出在推出与起飞之间的等待。
 */
@Entity
@Table(name = "caliber_result_item")
public class CaliberResultItem {

    @EmbeddedId
    private CaliberResultItemId id;

    @Column(nullable = false, length = 16)
    private String finalStatus;

    @Column(nullable = false)
    private boolean counted;

    private Boolean normal;

    private Integer delayMinutes;

    /** 该口径时间戳来源对应的事实时间。 */
    private Instant referenceTime;

    private Long scheduleVersionId;

    @Column(nullable = false)
    private int lastChangeVersion;

    /** 该航班本行是否受补录事件影响（原始/补齐区间分列标注）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackfillScope backfillScope = BackfillScope.ORIGINAL;

    protected CaliberResultItem() {
    }

    public CaliberResultItem(CaliberResultItemId id, String finalStatus, boolean counted, Boolean normal,
                             Integer delayMinutes, Instant referenceTime, Long scheduleVersionId,
                             int lastChangeVersion, BackfillScope backfillScope) {
        this.id = id;
        this.finalStatus = finalStatus;
        this.counted = counted;
        this.normal = normal;
        this.delayMinutes = delayMinutes;
        this.referenceTime = referenceTime;
        this.scheduleVersionId = scheduleVersionId;
        this.lastChangeVersion = lastChangeVersion;
        this.backfillScope = backfillScope;
    }

    public CaliberResultItemId getId() {
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

    public Instant getReferenceTime() {
        return referenceTime;
    }

    public Long getScheduleVersionId() {
        return scheduleVersionId;
    }

    public int getLastChangeVersion() {
        return lastChangeVersion;
    }

    public BackfillScope getBackfillScope() {
        return backfillScope;
    }
}
