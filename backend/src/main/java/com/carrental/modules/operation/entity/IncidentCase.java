package com.carrental.modules.operation.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "incident_cases")
public class IncidentCase {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String bookingId;
    public String reportedBy;
    public String kind;
    public Instant occurredAt;
    public Instant reportedAt;
    public String description;
    public String evidenceReference;
    public String status;
    public long assessedDamage;
    public long thirdPartyCoverage;
    public long requestedAmount;
    public String responsibilityBasis;
}
