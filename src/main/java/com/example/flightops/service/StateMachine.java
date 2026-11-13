package com.example.flightops.service;

import com.example.flightops.domain.FlightStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.example.flightops.domain.FlightStatus.BOARDING;
import static com.example.flightops.domain.FlightStatus.CANCELLED;
import static com.example.flightops.domain.FlightStatus.DELAYED;
import static com.example.flightops.domain.FlightStatus.DEPARTED;
import static com.example.flightops.domain.FlightStatus.DIVERTED;
import static com.example.flightops.domain.FlightStatus.ESTIMATED;
import static com.example.flightops.domain.FlightStatus.LANDED;
import static com.example.flightops.domain.FlightStatus.PLANNED;
import static com.example.flightops.domain.FlightStatus.PUSHBACK;
import static com.example.flightops.domain.FlightStatus.RESTORED;

/**
 * 航班状态机。合法路径含：取消后恢复、起飞后备降、备降后取消；
 * 未推出直接落地（LANDED 只能来自 DEPARTED/DIVERTED）拒绝并告警。
 */
@Component
public class StateMachine {

    private static final Map<FlightStatus, Set<FlightStatus>> TRANSITIONS = Map.of(
            PLANNED, EnumSet.of(ESTIMATED, DELAYED, BOARDING, CANCELLED),
            ESTIMATED, EnumSet.of(ESTIMATED, DELAYED, BOARDING, CANCELLED),
            DELAYED, EnumSet.of(ESTIMATED, BOARDING, CANCELLED),
            BOARDING, EnumSet.of(PUSHBACK, DELAYED, CANCELLED),
            PUSHBACK, EnumSet.of(DEPARTED, CANCELLED),
            DEPARTED, EnumSet.of(LANDED, DIVERTED),
            DIVERTED, EnumSet.of(DEPARTED, LANDED, CANCELLED),
            CANCELLED, EnumSet.of(RESTORED),
            RESTORED, EnumSet.of(ESTIMATED, DELAYED, BOARDING, CANCELLED),
            LANDED, EnumSet.noneOf(FlightStatus.class)
    );

    /** 允许时间更正（补录）的目标状态：对已到过的状态改写其业务时间。 */
    private static final Set<FlightStatus> TIME_CORRECTABLE = EnumSet.of(DEPARTED, LANDED);

    public boolean isLegal(FlightStatus from, FlightStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public Set<FlightStatus> legalTargets(FlightStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean isTimeCorrectable(FlightStatus status) {
        return TIME_CORRECTABLE.contains(status);
    }

    public boolean isTerminal(FlightStatus status) {
        return legalTargets(status).isEmpty();
    }
}
