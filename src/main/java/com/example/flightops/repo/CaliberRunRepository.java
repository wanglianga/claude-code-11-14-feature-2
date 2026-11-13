package com.example.flightops.repo;

import com.example.flightops.domain.CaliberRun;
import com.example.flightops.domain.CaliberRunId;
import com.example.flightops.domain.CaliberRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CaliberRunRepository extends JpaRepository<CaliberRun, CaliberRunId> {

    List<CaliberRun> findByServiceDateOrderByCaliberCodeAscRunNoAsc(LocalDate serviceDate);

    List<CaliberRun> findByServiceDateAndCaliberCodeOrderByRunNoAsc(LocalDate serviceDate, String caliberCode);

    Optional<CaliberRun> findByServiceDateAndCaliberCodeAndRunNo(LocalDate serviceDate, String caliberCode, int runNo);

    Optional<CaliberRun> findTopByServiceDateAndCaliberCodeOrderByRunNoDesc(LocalDate serviceDate, String caliberCode);

    Optional<CaliberRun> findTopByServiceDateAndCaliberCodeAndStatusOrderByRunNoDesc(
            LocalDate serviceDate, String caliberCode, CaliberRunStatus status);

    /** 分区表复合主键：id 由序列显式赋值。 */
    @Query(value = "SELECT nextval('caliber_run_id_seq')", nativeQuery = true)
    Long nextId();
}
