package com.example.flightops.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** 日报主键：id + 报表日期（分区键）。 */
public class DailyReportId implements Serializable {

    private Long id;
    private LocalDate reportDate;

    public DailyReportId() {
    }

    public DailyReportId(Long id, LocalDate reportDate) {
        this.id = id;
        this.reportDate = reportDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DailyReportId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(reportDate, that.reportDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, reportDate);
    }
}
