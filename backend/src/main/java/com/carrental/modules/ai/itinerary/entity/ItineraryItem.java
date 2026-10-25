package com.carrental.modules.ai.itinerary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "itinerary_items")
public class ItineraryItem {
    @Id
    public String id = UUID.randomUUID().toString();

    @Column(name = "day_id", nullable = false, length = 36)
    public String dayId;

    @Column(name = "location_id", nullable = false, length = 36)
    public String locationId;

    @Column(name = "sort_order", nullable = false)
    public int sortOrder;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String snapshot;
}
