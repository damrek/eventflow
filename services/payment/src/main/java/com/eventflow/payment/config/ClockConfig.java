package com.eventflow.payment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** One clock for payment timestamps, so tests can reason about attempts without sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
