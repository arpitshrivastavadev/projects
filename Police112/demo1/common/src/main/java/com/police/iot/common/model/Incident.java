package com.police.iot.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "T_INCIDENT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Incident implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "TENANT_ID", nullable = false)
    private String tenantId;

    private String type;
    private String description;
    private String location;
    private String severity;

    @Column(name = "CREATED_AT")
    private Instant timestamp;

    @ManyToOne
    @JoinColumn(name = "OFFICER_ID")
    private Officer officer;

    @ManyToOne
    @JoinColumn(name = "VEHICLE_ID")
    private PatrolVehicle vehicle;

    @PrePersist
    protected void onCreate() {
        timestamp = Instant.now();
    }
}
