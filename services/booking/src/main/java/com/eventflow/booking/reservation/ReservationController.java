package com.eventflow.booking.reservation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.booking.reservation.dto.CreateReservationRequest;
import com.eventflow.booking.reservation.dto.ReservationResponse;
import com.eventflow.booking.shared.web.CurrentUserProvider;
import com.eventflow.booking.shared.web.rate.FixedWindowRateLimiter;
import com.eventflow.booking.shared.web.rate.RateLimitEndpoint;
import com.eventflow.booking.waitingroom.WaitingRoomService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservations;
    private final CurrentUserProvider currentUser;
    private final FixedWindowRateLimiter rateLimiter;
    private final WaitingRoomService waitingRoom;

    @PostMapping
    public ResponseEntity<ReservationResponse> create(
            @Valid @RequestBody CreateReservationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "X-Admission-Token", required = false) String admissionToken,
            HttpServletRequest httpRequest) {
        String userId = currentUser.userId(httpRequest);
        rateLimiter.check(RateLimitEndpoint.RESERVATION_CREATE, userId);
        // The gate only applies while this event has a queue; PostgreSQL still decides the hold.
        waitingRoom.requireAdmission(request.eventId(), userId, admissionToken);
        ReservationResult result = reservations.create(
                userId, new IdempotencyKey(idempotencyKeyHeader), request.eventId(), request.toSelections());
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse get(@PathVariable UUID reservationId, HttpServletRequest httpRequest) {
        return ReservationResponse.from(reservations.get(currentUser.userId(httpRequest), reservationId));
    }

    @PostMapping("/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId, HttpServletRequest httpRequest) {
        return ReservationResponse.from(reservations.cancel(currentUser.userId(httpRequest), reservationId));
    }

    @PostMapping("/{reservationId}/checkout")
    public ReservationResponse checkout(@PathVariable UUID reservationId, HttpServletRequest httpRequest) {
        return ReservationResponse.from(reservations.checkout(currentUser.userId(httpRequest), reservationId));
    }
}
