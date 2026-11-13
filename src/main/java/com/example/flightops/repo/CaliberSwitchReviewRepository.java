package com.example.flightops.repo;

import com.example.flightops.domain.CaliberSwitchReview;
import com.example.flightops.domain.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CaliberSwitchReviewRepository extends JpaRepository<CaliberSwitchReview, Long> {

    List<CaliberSwitchReview> findByServiceDateOrderByIdAsc(LocalDate serviceDate);

    List<CaliberSwitchReview> findByStatusOrderByIdAsc(ReviewStatus status);

    /** 当前主口径：最近一条 APPLIED 记录。 */
    Optional<CaliberSwitchReview> findTopByStatusOrderByAppliedAtDescIdDesc(ReviewStatus status);

    List<CaliberSwitchReview> findAllByOrderByIdDesc();
}
