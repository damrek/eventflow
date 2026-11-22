package com.eventflow.catalog.venue;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venues;

    @Transactional
    public Venue create(String name, String address, int capacity) {
        return venues.save(Venue.create(name, address, capacity));
    }

    @Transactional(readOnly = true)
    public List<Venue> list() {
        return venues.findAll(Sort.by(Sort.Direction.ASC, "name"));
    }
}
