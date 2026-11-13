package com.example.flightops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 变更日志（分区表 flight_change_log）：校验通过的变更不覆盖旧值，
 * 以 +1（计入）/ -1（冲抵）成对写入，下游按实例主键逐条冲抵得到最终状态。
 */
@Entity
@Table(name = "flight_change_log")
public class ChangeLogEntry {

    @EmbeddedId
    private ChangeLogId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ChangeEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FlightStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FlightStatus toStatus;

    /** +1 计入 / -1 冲抵。 */
    @Column(nullable = false)
    private short sign;

    /** 业务发生时间（该事实对应的时间，如实际起飞时刻）。 */
    private Instant eventTime;

    @Column(nullable = false, updatable = false)
    private Instant ingestTime = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChangeSource source = ChangeSource.REALTIME;

    /** 是否超迟到窗口。 */
    @Column(nullable = false)
    private boolean late = false;

    @Column(length = 512)
    private String note;

    protected ChangeLogEntry() {
    }

    public ChangeLogEntry(ChangeLogId id, ChangeEventType eventType,
                          FlightStatus fromStatus, FlightStatus toStatus, short sign,
                          Instant eventTime, ChangeSource source, boolean late, String note) {
        this.id = id;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.sign = sign;
        this.eventTime = eventTime;
        this.source = source;
        this.late = late;
        this.note = note;
    }

    public ChangeLogId getId() {
        return id;
    }

    public ChangeEventType getEventType() {
        return eventType;
    }

    public FlightStatus getFromStatus() {
        return fromStatus;
    }

    public FlightStatus getToStatus() {
        return toStatus;
    }

    public short getSign() {
        return sign;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getIngestTime() {
        return ingestTime;
    }

    public ChangeSource getSource() {
        return source;
    }

    public boolean isLate() {
        return late;
    }

    public String getNote() {
        return note;
    }
}
