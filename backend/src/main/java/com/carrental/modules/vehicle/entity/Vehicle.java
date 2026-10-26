package com.carrental.modules.vehicle.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
public class Vehicle {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String name;
    public String plate;
    public String brandId;
    public String categoryId;
    public String homeBranchId;
    public String currentBranchId;
    public int seats;
    public String transmission;
    public long dailyPrice;
    public String status;
    public String description;
    @Column(updatable = false) public Instant createdAt;
}
