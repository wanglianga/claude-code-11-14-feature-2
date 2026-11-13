package com.example.flightops.repo;

import com.example.flightops.domain.DownstreamBinding;
import com.example.flightops.domain.DownstreamTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DownstreamBindingRepository extends JpaRepository<DownstreamBinding, Long> {

    List<DownstreamBinding> findByTargetOrderByIdDesc(DownstreamTarget target);

    List<DownstreamBinding> findByActiveTrueOrderByIdDesc();

    Optional<DownstreamBinding> findFirstByTargetAndActiveTrueOrderByIdDesc(DownstreamTarget target);

    Optional<DownstreamBinding> findFirstByTargetAndPeriodAndActiveTrueOrderByIdDesc(
            DownstreamTarget target, String period);

    List<DownstreamBinding> findByCaliberCodeAndCaliberVersionOrderByIdDesc(String caliberCode, int caliberVersion);
}
