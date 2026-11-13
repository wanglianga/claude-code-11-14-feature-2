package com.example.flightops.repo;

import com.example.flightops.domain.ReportDiff;
import com.example.flightops.domain.ReportDiffId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ReportDiffRepository extends JpaRepository<ReportDiff, ReportDiffId> {

    List<ReportDiff> findByIdReportDateAndIdFromVersionAndIdToVersion(
            LocalDate reportDate, Integer fromVersion, Integer toVersion);
}
