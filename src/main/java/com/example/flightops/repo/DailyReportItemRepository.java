package com.example.flightops.repo;

import com.example.flightops.domain.DailyReportItem;
import com.example.flightops.domain.DailyReportItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyReportItemRepository extends JpaRepository<DailyReportItem, DailyReportItemId> {

    List<DailyReportItem> findByIdReportIdOrderByIdFlightNoAscIdLegNoAsc(Long reportId);
}
