package com.eventflow.simulator;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The only endpoint of the simulator. It is bound to the same address as a real provider would
 * be, so Payment's HTTP client, timeouts and circuit breaker behave exactly as in production.
 */
@RestController
@RequestMapping("/simulator")
@RequiredArgsConstructor
@Slf4j
public class AuthorizationController {

    private final ProviderBehaviour behaviour = new ProviderBehaviour();
    private final SimulatorProperties properties;

    @PostMapping("/authorizations")
    public ResponseEntity<?> authorize(@Valid @RequestBody AuthorizationRequest request) {
        ProviderBehaviour.Outcome outcome = behaviour.decide(request.amount());
        log.info("Simulated provider decision {} for payment {} ({})",
                outcome, request.paymentId(), request.amount());
        return switch (outcome) {
            case DECLINED -> ResponseEntity.ok(new AuthorizationResponse(
                    AuthorizationResponse.REJECTED, reference(), "Simulated decline: amount below 10.00"));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ProviderError(
                            "SIMULATED_PROVIDER_ERROR", "Simulated outage for amounts ending in .98"));
            case SLOW -> ResponseEntity.ok(slowApproval());
            case APPROVED -> ResponseEntity.ok(approval());
        };
    }

    private AuthorizationResponse slowApproval() {
        try {
            Thread.sleep(properties.timeoutDelay().toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return approval();
    }

    private AuthorizationResponse approval() {
        return new AuthorizationResponse(AuthorizationResponse.APPROVED, reference(), null);
    }

    private static String reference() {
        return "SIM-" + UUID.randomUUID();
    }
}
