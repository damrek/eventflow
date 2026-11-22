package com.eventflow.catalog.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVenueRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 400) String address,
        @Min(1) int capacity) {
}
