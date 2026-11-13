package com.example.flightops.repo;

import com.example.flightops.domain.CaliberDefinition;
import com.example.flightops.domain.CaliberStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaliberDefinitionRepository extends JpaRepository<CaliberDefinition, Long> {

    List<CaliberDefinition> findByCodeOrderByVersionAsc(String code);

    Optional<CaliberDefinition> findByCodeAndVersion(String code, int version);

    Optional<CaliberDefinition> findTopByCodeOrderByVersionDesc(String code);

    List<CaliberDefinition> findByStatusOrderByCodeAscVersionAsc(CaliberStatus status);

    boolean existsByCodeAndVersion(String code, int version);
}
