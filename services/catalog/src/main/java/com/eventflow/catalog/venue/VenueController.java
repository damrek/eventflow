package com.eventflow.catalog.venue;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.eventflow.catalog.venue.dto.CreateVenueRequest;
import com.eventflow.catalog.venue.dto.VenueResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenueResponse create(@Valid @RequestBody CreateVenueRequest request) {
        return VenueResponse.from(venueService.create(request.name(), request.address(), request.capacity()));
    }

    @GetMapping
    public List<VenueResponse> list() {
        return venueService.list().stream().map(VenueResponse::from).toList();
    }
}
