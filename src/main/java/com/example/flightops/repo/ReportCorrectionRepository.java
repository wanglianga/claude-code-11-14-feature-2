package com.example.flightops.repo;

import com.example.flightops.domain.ReportCorrection;
import com.example.flightops.domain.ReportCorrectionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ReportCorrectionRepository extends JpaRepository<ReportCorrection, ReportCorrectionId> {

    List<ReportCorrection> findByIdReportDateOrderByIdCorrectionReportIdAscIdFlightNoAsc(LocalDate reportDate);
}
