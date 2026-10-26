package com.carrental.modules.user.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "users")
public class Account {
    @Id public String id = UUID.randomUUID().toString();
    @Version public long version;
    public String email;
    public String passwordHash;
    public String fullName;
    public String phone;
    public String role;
    public String status;
    public long tokenVersion;
}
