package com.police.iot.common.model;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "T_PATROL_VEHICLE")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatrolVehicle implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "TENANT_ID", nullable = false)
    private String tenantId;

    @Column(name = "PLATE_NUMBER", nullable = false, unique = true)
    private String plateNumber;

    @Column(name = "VEHICLE_TYPE")
    private String vehicleType;

    @Column(name = "EDGE_NODE_ID")
    private String edgeNodeId;

    private String status;

    private String location; // GeoJSON or simple string lat,long

    @Column(name = "CREATED_AT")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
