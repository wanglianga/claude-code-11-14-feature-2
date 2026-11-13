package com.example.flightops.repo;

import com.example.flightops.domain.ChangeLogEntry;
import com.example.flightops.domain.ChangeLogId;
import com.example.flightops.domain.FlightStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ChangeLogRepository extends JpaRepository<ChangeLogEntry, ChangeLogId> {

    List<ChangeLogEntry> findByIdFlightNoAndIdServiceDateAndIdLegNoOrderByIdChangeVersionAsc(
            String flightNo, LocalDate serviceDate, Integer legNo);

    List<ChangeLogEntry> findByIdServiceDateOrderByIdFlightNoAscIdLegNoAscIdChangeVersionAsc(LocalDate serviceDate);

    /** 某状态最近一次 +1 事实（冲抵/更正时定位被冲抵的事实，取第一条）。 */
    @Query("select c from ChangeLogEntry c where c.id.flightNo = :flightNo and c.id.serviceDate = :serviceDate "
            + "and c.id.legNo = :legNo and c.toStatus = :status and c.sign = 1 "
            + "order by c.id.changeVersion desc")
    List<ChangeLogEntry> findLastPositiveForStatus(@Param("flightNo") String flightNo,
                                                   @Param("serviceDate") LocalDate serviceDate,
                                                   @Param("legNo") Integer legNo,
                                                   @Param("status") FlightStatus status,
                                                   Pageable pageable);

    /** 版本间差异定位：两版之间该实例发生的变更。 */
    @Query("select c from ChangeLogEntry c where c.id.flightNo = :flightNo and c.id.serviceDate = :serviceDate "
            + "and c.id.legNo = :legNo and c.id.changeVersion > :fromVersion "
            + "and c.id.changeVersion <= :toVersion order by c.id.changeVersion asc")
    List<ChangeLogEntry> findInVersionRange(@Param("flightNo") String flightNo,
                                            @Param("serviceDate") LocalDate serviceDate,
                                            @Param("legNo") Integer legNo,
                                            @Param("fromVersion") int fromVersion,
                                            @Param("toVersion") int toVersion);
}
