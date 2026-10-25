package com.carrental.modules.branch.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "branch_routes")
public class BranchRoute {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String pickupBranchId;
    public String returnBranchId;
    public long fee;
    public boolean enabled;
}
