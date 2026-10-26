package com.carrental.modules.user.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "driver_licenses")
public class DriverLicense {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String userId;
    public String licenseNumber;
    public String scheme;
    public String licenseClass;
    public LocalDate expiresOn;
    public boolean automaticOnly;
    public String status;
    public String checkedBy;
    public Instant checkedAt;
    public String frontPhoto;
    public String backPhoto;
    public String photoReviewStatus = "MISSING";
    public String photoReviewReason;
    public String photoReviewedBy;
    public Instant photoReviewedAt;
}
