package com.eventflow.booking.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Canonical fingerprint of a reservation request. Built from the semantic fields (not the raw
 * body) so a retry that differs only in JSON formatting is still recognised as the same request,
 * while a different event, ticket type or quantity is not.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(UUID eventId, List<TicketSelection> selections) {
        String canonical = eventId
                + "|"
                + selections.stream()
                        .sorted(Comparator.comparing(TicketSelection::ticketTypeId))
                        .map(selection -> selection.ticketTypeId() + "x" + selection.quantity())
                        .collect(Collectors.joining(","));
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the platform", exception);
        }
    }
}
