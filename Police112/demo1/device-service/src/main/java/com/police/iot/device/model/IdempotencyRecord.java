package com.police.iot.device.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "T_IDEMPOTENCY_RECORD",
        uniqueConstraints = @UniqueConstraint(name = "UK_IDEMPOTENCY_SCOPE", columnNames = {
                "TENANT_ID", "ROUTE", "HTTP_METHOD", "IDEMPOTENCY_KEY"
        }))
@Getter
@Setter
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "TENANT_ID", nullable = false)
    private String tenantId;

    @Column(name = "ROUTE", nullable = false)
    private String route;

    @Column(name = "HTTP_METHOD", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "IDEMPOTENCY_KEY", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "REQUEST_HASH", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "RESPONSE_STATUS")
    private Integer responseStatus;

    @Column(name = "RESPONSE_BODY", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "CREATED_AT", nullable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
