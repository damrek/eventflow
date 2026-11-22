package com.eventflow.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EventFlow Catalog API")
                        .version("1.0.0")
                        .description("""
                                Venues, events, ticket types and publication.
                                Errors follow RFC 9457 (application/problem+json) and include a stable
                                `code` plus the request `traceId`."""));
    }
}
