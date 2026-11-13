package com.example.flightops.repo;

import com.example.flightops.domain.DailyReport;
import com.example.flightops.domain.DailyReportId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, DailyReportId> {

    List<DailyReport> findByReportDateOrderByVersionAsc(LocalDate reportDate);

    Optional<DailyReport> findByReportDateAndVersion(LocalDate reportDate, int version);

    Optional<DailyReport> findTopByReportDateOrderByVersionDesc(LocalDate reportDate);

    /** 切换评审撞锁检查：该日期是否存在锁定日报（锁定优先，切换顺延）。 */
    List<DailyReport> findByReportDateAndStatus(LocalDate reportDate, String status);

    /** 分区表复合主键：id 由序列显式赋值。 */
    @Query(value = "SELECT nextval('daily_report_id_seq')", nativeQuery = true)
    Long nextId();
}
