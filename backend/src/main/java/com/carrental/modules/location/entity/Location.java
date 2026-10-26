package com.carrental.modules.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "locations")
public class Location {
    @Column(name = "visit_metadata", columnDefinition = "TEXT")
    public String visitMetadata;
    @Id
    public String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    public LocationCategory category;

    @Column(nullable = false, length = 200)
    public String name;

    @Column(nullable = false, precision = 10, scale = 7)
    public BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    public BigDecimal longitude;

    @Column(nullable = false, length = 10)
    public String status;
}
