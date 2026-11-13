package com.example.flightops.repo;

import com.example.flightops.domain.CaliberBackfillSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaliberBackfillSegmentRepository extends JpaRepository<CaliberBackfillSegment, Long> {

    List<CaliberBackfillSegment> findByCaliberCodeOrderByRangeFromAscIdAsc(String caliberCode);

    List<CaliberBackfillSegment> findAllByOrderByRangeFromAscIdAsc();
}
