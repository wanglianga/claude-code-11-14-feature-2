package com.example.flightops.repo;

import com.example.flightops.domain.CaliberSwitchConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CaliberSwitchConflictRepository extends JpaRepository<CaliberSwitchConflict, Long> {

    List<CaliberSwitchConflict> findByServiceDateOrderByIdAsc(LocalDate serviceDate);

    List<CaliberSwitchConflict> findByResolvedFalseOrderByIdAsc();

    List<CaliberSwitchConflict> findAllByOrderByIdDesc();
}
