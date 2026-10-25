package com.carrental.modules.booking.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String userId;
    public String vehicleId;
    public String pickupBranchId;
    public String returnBranchId;
    public String actualReturnBranchId;
    public Instant startAt;
    public Instant endAt;
    public Instant createdAt;
    public Instant expiresAt;
    public String status;
    public long dailyPrice;
    public int rentalDays;
    public long rentalTotal;
    public long oneWayFee;
    public long total;
    public String customerName;
    public String vehicleName;
    public String pickupName;
    public String returnName;
    public String policyVersion;
    public String returnPolicyVersion;
    @Column(columnDefinition = "TEXT") public String returnPolicySnapshot;
    public Instant returnPolicyAcceptedAt;
    public boolean depositRequired;
    public long depositAmount;
    public String depositPolicy;
    public Instant depositApprovedAt;
    public String depositApprovedBy;
    public Long depositLicenseVersion;
    public String depositTxnRef;
    public String cancellationKind;
    public String depositMethod;
    public String depositMode;
    public Instant originalHoldUntil;
    public Instant paymentDueAt;
    public Instant reviewDueAt;
    public Instant reportedAt;
    public String depositReceiverSnapshot;
}
