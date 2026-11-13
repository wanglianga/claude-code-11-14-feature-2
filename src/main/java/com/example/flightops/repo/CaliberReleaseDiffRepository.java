package com.example.flightops.repo;

import com.example.flightops.domain.CaliberReleaseDiff;
import com.example.flightops.domain.DiffAttribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CaliberReleaseDiffRepository extends JpaRepository<CaliberReleaseDiff, CaliberReleaseDiff.Id> {

    List<CaliberReleaseDiff> findByIdServiceDateAndIdPublishRunIdOrderByIdFlightNoAscIdLegNoAscIdFieldAsc(
            LocalDate serviceDate, Long publishRunId);

    List<CaliberReleaseDiff> findByIdServiceDateAndIdPublishRunIdAndAttribution(
            LocalDate serviceDate, Long publishRunId, DiffAttribution attribution);
}
