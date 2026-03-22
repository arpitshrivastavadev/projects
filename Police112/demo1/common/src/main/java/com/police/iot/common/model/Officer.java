package com.police.iot.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "T_OFFICER")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Officer implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "TENANT_ID", nullable = false)
    private String tenantId;

    @Column(name = "USER_ID", nullable = false)
    private String userId;

    @Column(name = "OFFICER_NAME")
    private String name;

    @Column(name = "BADGE_NUMBER", unique = true)
    private String badgeNumber;

    private String status;

    @Column(name = "CREATED_AT")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
