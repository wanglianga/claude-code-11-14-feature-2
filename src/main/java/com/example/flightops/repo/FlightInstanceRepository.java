package com.example.flightops.repo;

import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightInstanceId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FlightInstanceRepository extends JpaRepository<FlightInstance, FlightInstanceId> {

    List<FlightInstance> findByIdServiceDateOrderByIdFlightNoAscIdLegNoAsc(LocalDate serviceDate);

    /** 摄入事件时串行化同一实例的并发变更。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FlightInstance f where f.id = :id")
    Optional<FlightInstance> findByIdForUpdate(@Param("id") FlightInstanceId id);
}
