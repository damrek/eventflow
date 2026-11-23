package com.eventflow.simulator;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Authorization request as Payment sends it; amounts drive the simulated outcome. */
public record AuthorizationRequest(
        @NotNull UUID paymentId,
        @NotNull UUID reservationId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}
