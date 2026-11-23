package com.eventflow.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventFlow Notification API")
                        .version("1.0.0")
                        .description("""
                                User notification mailbox fed by booking events.
                                Errors follow RFC 9457 (application/problem+json) and include a stable
                                `code` plus the request `traceId`."""));
    }
}
