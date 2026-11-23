package com.eventflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** The amount rules are the whole contract of the simulator, so they are pinned here. */
class ProviderBehaviourTest {

    private final ProviderBehaviour behaviour = new ProviderBehaviour();

    @Test
    void declinesBelowTheFloor() {
        assertThat(behaviour.decide(new BigDecimal("9.99"))).isEqualTo(ProviderBehaviour.Outcome.DECLINED);
        assertThat(behaviour.decide(new BigDecimal("0.01"))).isEqualTo(ProviderBehaviour.Outcome.DECLINED);
    }

    @Test
    void failsForAmountsEndingIn98() {
        assertThat(behaviour.decide(new BigDecimal("100.98"))).isEqualTo(ProviderBehaviour.Outcome.UNAVAILABLE);
    }

    @Test
    void answersLateForAmountsEndingIn99() {
        assertThat(behaviour.decide(new BigDecimal("100.99"))).isEqualTo(ProviderBehaviour.Outcome.SLOW);
    }

    @Test
    void approvesEverythingElse() {
        assertThat(behaviour.decide(new BigDecimal("10.00"))).isEqualTo(ProviderBehaviour.Outcome.APPROVED);
        assertThat(behaviour.decide(new BigDecimal("110.00"))).isEqualTo(ProviderBehaviour.Outcome.APPROVED);
        assertThat(behaviour.decide(new BigDecimal("50.97"))).isEqualTo(ProviderBehaviour.Outcome.APPROVED);
    }
}
