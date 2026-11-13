package com.example.flightops.service;

import com.example.flightops.domain.FlightStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineTest {

    private final StateMachine stateMachine = new StateMachine();

    @Test
    void legalPaths_cancelRestore_divertAfterDeparture_cancelAfterDivert() {
        // 取消后恢复
        assertThat(stateMachine.isLegal(FlightStatus.CANCELLED, FlightStatus.RESTORED)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.RESTORED, FlightStatus.BOARDING)).isTrue();
        // 起飞后备降
        assertThat(stateMachine.isLegal(FlightStatus.DEPARTED, FlightStatus.DIVERTED)).isTrue();
        // 备降后取消 / 续飞 / 备降场落地
        assertThat(stateMachine.isLegal(FlightStatus.DIVERTED, FlightStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.DIVERTED, FlightStatus.DEPARTED)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.DIVERTED, FlightStatus.LANDED)).isTrue();
    }

    @Test
    void normalFlow() {
        assertThat(stateMachine.isLegal(FlightStatus.PLANNED, FlightStatus.ESTIMATED)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.ESTIMATED, FlightStatus.DELAYED)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.DELAYED, FlightStatus.BOARDING)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.BOARDING, FlightStatus.PUSHBACK)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.PUSHBACK, FlightStatus.DEPARTED)).isTrue();
        assertThat(stateMachine.isLegal(FlightStatus.DEPARTED, FlightStatus.LANDED)).isTrue();
    }

    @Test
    void landingWithoutPushbackIsRejected() {
        // 未推出直接落地：全部拒绝
        assertThat(stateMachine.isLegal(FlightStatus.PLANNED, FlightStatus.LANDED)).isFalse();
        assertThat(stateMachine.isLegal(FlightStatus.ESTIMATED, FlightStatus.LANDED)).isFalse();
        assertThat(stateMachine.isLegal(FlightStatus.DELAYED, FlightStatus.LANDED)).isFalse();
        assertThat(stateMachine.isLegal(FlightStatus.BOARDING, FlightStatus.LANDED)).isFalse();
        // 未起飞直接落地同样拒绝
        assertThat(stateMachine.isLegal(FlightStatus.PUSHBACK, FlightStatus.LANDED)).isFalse();
    }

    @Test
    void landedIsTerminal() {
        assertThat(stateMachine.legalTargets(FlightStatus.LANDED)).isEmpty();
        assertThat(stateMachine.isTerminal(FlightStatus.LANDED)).isTrue();
    }

    @Test
    void cancelledCannotDepartWithoutRestore() {
        assertThat(stateMachine.isLegal(FlightStatus.CANCELLED, FlightStatus.BOARDING)).isFalse();
        assertThat(stateMachine.isLegal(FlightStatus.CANCELLED, FlightStatus.DEPARTED)).isFalse();
    }
}
