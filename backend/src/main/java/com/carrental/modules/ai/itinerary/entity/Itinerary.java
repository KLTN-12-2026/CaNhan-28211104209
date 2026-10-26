package com.carrental.modules.ai.itinerary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "itineraries")
public class Itinerary {
    @Id
    public String id = UUID.randomUUID().toString();

    @Column(name = "user_id", nullable = false, length = 36)
    public String userId;

    @Column(nullable = false, length = 200)
    public String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String criteria;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
