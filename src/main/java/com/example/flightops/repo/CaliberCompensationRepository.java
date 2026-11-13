package com.example.flightops.repo;

import com.example.flightops.domain.CaliberCompensation;
import com.example.flightops.domain.CaliberCompensationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface CaliberCompensationRepository extends JpaRepository<CaliberCompensation, CaliberCompensationId> {

    // 实体使用 @IdClass：id/serviceDate 是实体上的直接属性，路径不带 id. 前缀
    List<CaliberCompensation> findByServiceDateOrderByIdAsc(LocalDate serviceDate);

    List<CaliberCompensation> findByServiceDateAndCaliberCodeOrderByIdAsc(LocalDate serviceDate, String caliberCode);

    List<CaliberCompensation> findByServiceDateAndFlightNoAndLegNoOrderByIdAsc(
            LocalDate serviceDate, String flightNo, int legNo);

    @Query(value = "SELECT nextval('caliber_compensation_id_seq')", nativeQuery = true)
    Long nextId();
}
