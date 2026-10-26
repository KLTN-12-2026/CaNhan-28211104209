package com.carrental.modules.vehicle.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "amenities")
public class Amenity {
    @Id
    public String id;
    public String name;
}