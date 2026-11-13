package com.example.flightops.repo;

import com.example.flightops.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByServiceDateOrderByCreatedAtDesc(LocalDate serviceDate);

    List<Alert> findByAcknowledgedFalseOrderByCreatedAtDesc();
}
