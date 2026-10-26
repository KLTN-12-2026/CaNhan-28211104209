package com.carrental.modules.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "location_categories")
public class LocationCategory {
    @Id
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false, length = 100, unique = true)
    public String name;
}