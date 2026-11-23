package com.eventflow.booking.api;

/**
 * JSON bodies shared by the Booking HTTP tests. Kept as text so a test proves the wire format
 * the client actually sends, not a re-serialized DTO.
 */
final class ApiFixtures {

    static final String USER = "user-1";
    static final String OTHER_USER = "user-2";

    private ApiFixtures() {
    }

    static String inventory(String ticketTypeId, String price, int capacity) {
        return """
                {"items":[{"ticketTypeId":"%s","price":%s,"capacity":%d}]}"""
                .formatted(ticketTypeId, price, capacity);
    }

    static String inventoryTwoTypes(String generalId, String vipId) {
        return """
                {"items":[{"ticketTypeId":"%s","price":25.00,"capacity":400},
                          {"ticketTypeId":"%s","price":60.00,"capacity":50}]}"""
                .formatted(generalId, vipId);
    }

    static String reservation(String eventId, String ticketTypeId, int quantity) {
        return """
                {"eventId":"%s","items":[{"ticketTypeId":"%s","quantity":%d}]}"""
                .formatted(eventId, ticketTypeId, quantity);
    }

    static String reservationTwoTypes(String eventId, String generalId, int generalQuantity, String vipId, int vipQuantity) {
        return """
                {"eventId":"%s","items":[{"ticketTypeId":"%s","quantity":%d},
                                         {"ticketTypeId":"%s","quantity":%d}]}"""
                .formatted(eventId, generalId, generalQuantity, vipId, vipQuantity);
    }
}
