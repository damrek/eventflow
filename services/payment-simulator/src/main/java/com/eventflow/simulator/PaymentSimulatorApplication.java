package com.eventflow.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A provider double, not a business service: Payment calls it over HTTP so the circuit breaker
 * protects a real remote dependency. It owns no database, publishes no events and keeps no state,
 * which makes it safe to start, kill and restart at will.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentSimulatorApplication.class, args);
    }
}
