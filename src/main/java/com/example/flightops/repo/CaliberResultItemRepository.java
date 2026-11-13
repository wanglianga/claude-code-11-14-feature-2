package com.example.flightops.repo;

import com.example.flightops.domain.CaliberResultItem;
import com.example.flightops.domain.CaliberResultItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CaliberResultItemRepository extends JpaRepository<CaliberResultItem, CaliberResultItemId> {

    List<CaliberResultItem> findByIdRunIdOrderByIdFlightNoAscIdLegNoAsc(Long runId);

    List<CaliberResultItem> findByIdServiceDateAndIdFlightNoAndIdLegNoOrderByIdRunIdAsc(
            LocalDate serviceDate, String flightNo, int legNo);
}
