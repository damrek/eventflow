package com.eventflow.simulator;

import java.math.BigDecimal;

/**
 * The simulator's contract with its callers, expressed per amount so every test and demo is
 * deterministic without a mutable "next outcome" switch:
 *
 * <ul>
 *   <li>below {@code 10.00}: declined, as if the instrument had no funds;</li>
 *   <li>cents {@code .98}: HTTP 503, a provider outage the breaker must absorb;</li>
 *   <li>cents {@code .99}: the answer arrives after the caller's read timeout;</li>
 *   <li>anything else: approved.</li>
 * </ul>
 */
public record ProviderBehaviour() {

    private static final BigDecimal FLOOR = new BigDecimal("10.00");
    private static final BigDecimal PROVIDER_ERROR_CENTS = new BigDecimal("98");
    private static final BigDecimal SLOW_CENTS = new BigDecimal("99");

    public Outcome decide(BigDecimal amount) {
        if (amount.compareTo(FLOOR) < 0) {
            return Outcome.DECLINED;
        }
        BigDecimal cents = amount.remainder(BigDecimal.ONE).movePointRight(2);
        if (cents.compareTo(PROVIDER_ERROR_CENTS) == 0) {
            return Outcome.UNAVAILABLE;
        }
        if (cents.compareTo(SLOW_CENTS) == 0) {
            return Outcome.SLOW;
        }
        return Outcome.APPROVED;
    }

    public enum Outcome {
        APPROVED,
        DECLINED,
        UNAVAILABLE,
        SLOW
    }
}
