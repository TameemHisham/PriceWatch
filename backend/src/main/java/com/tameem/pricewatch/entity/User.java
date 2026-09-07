package com.tameem.pricewatch.entity;


import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false, length = 254)
    private String email;
    @Column(name = "password_hash")
    private String passwordHash;
    @Column(nullable = false, name = "created_at")
    private Instant createdAt;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
    @PrePersist // before a new entity is added to a database this annotation runs the function
    public void onPrePersist() {
        this.setCreatedAt(Instant.now());
    }
}
