package com.carrental.modules.branch.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "branches")
public class Branch {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String name;
    public String address;
    public String status;
}
