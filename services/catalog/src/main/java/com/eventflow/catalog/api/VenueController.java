package com.eventflow.catalog.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.catalog.api.dto.CreateVenueRequest;
import com.eventflow.catalog.api.dto.VenueResponse;
import com.eventflow.catalog.application.CatalogService;
import com.eventflow.catalog.application.CreateVenueCommand;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final CatalogService catalogService;

    public VenueController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenueResponse create(@Valid @RequestBody CreateVenueRequest request) {
        return CatalogMapper.toResponse(catalogService.createVenue(
                new CreateVenueCommand(request.name(), request.address(), request.capacity())));
    }

    @GetMapping
    public List<VenueResponse> list() {
        return catalogService.listVenues().stream().map(CatalogMapper::toResponse).toList();
    }
}
