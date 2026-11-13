package com.example.flightops.repo;

import com.example.flightops.domain.ScheduleVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ScheduleVersionRepository extends JpaRepository<ScheduleVersion, Long> {

    List<ScheduleVersion> findByFlightNoAndServiceDateAndLegNoOrderByVersionAsc(
            String flightNo, LocalDate serviceDate, int legNo);

    /** 判定用：业务发生时刻 at 当时生效的计划版本（effective_from <= at 的最新版本，取第一条）。 */
    @Query("select s from ScheduleVersion s where s.flightNo = :flightNo and s.serviceDate = :serviceDate "
            + "and s.legNo = :legNo and s.effectiveFrom <= :at order by s.version desc")
    List<ScheduleVersion> findEffectiveAt(@Param("flightNo") String flightNo,
                                          @Param("serviceDate") LocalDate serviceDate,
                                          @Param("legNo") int legNo,
                                          @Param("at") Instant at,
                                          Pageable pageable);
}
