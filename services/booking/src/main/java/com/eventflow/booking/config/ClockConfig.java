package com.eventflow.booking.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * One clock for reservation creation and expiration, so tests can reason about deadlines
     * without sleeping and {@code expiresAt} never depends on a database clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
