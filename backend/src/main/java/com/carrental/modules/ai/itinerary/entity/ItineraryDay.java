package com.carrental.modules.ai.itinerary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "itinerary_days")
public class ItineraryDay {
    @Id
    public String id = UUID.randomUUID().toString();

    @Column(name = "itinerary_id", nullable = false, length = 36)
    public String itineraryId;

    @Column(name = "trip_date", nullable = false)
    public LocalDate tripDate;
}
