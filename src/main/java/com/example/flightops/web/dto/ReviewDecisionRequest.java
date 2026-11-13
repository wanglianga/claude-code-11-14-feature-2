package com.example.flightops.web.dto;

import jakarta.validation.constraints.NotNull;

/** 切换评审决定：批准（撞锁定则顺延并留冲突记录）/ 驳回。 */
public record ReviewDecisionRequest(
        @NotNull Boolean approved,
        String comment) {
}
