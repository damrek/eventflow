package com.eventflow.catalog.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventflow.catalog.shared.messaging.MessagingProperties;

/**
 * Declares the durable topic exchange shared by every service. The auto-configured
 * {@code RabbitAdmin} declares it on startup; declaring it on both producer and consumer sides
 * is what makes a broker replacement self-healing without manual broker setup.
 */
@Configuration
public class RabbitTopology {

    @Bean
    public TopicExchange eventExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }
}
