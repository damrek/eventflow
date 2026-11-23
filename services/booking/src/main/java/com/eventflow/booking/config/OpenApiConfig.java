package com.eventflow.booking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventFlow Booking API")
                        .version("1.0.0")
                        .description("""
                                Inventory availability, reservations, expiration and the Redis waiting room.
                                Reservation creation requires `X-User-Id` and `Idempotency-Key`, plus
                                `X-Admission-Token` while the event's waiting room has a queue.
                                Errors follow RFC 9457 (application/problem+json) and include a stable
                                `code` plus the request `traceId`."""));
    }
}
