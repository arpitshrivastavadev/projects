package com.police.iot.common.dto;

import lombok.*;
import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoliceTelemetry implements Serializable {
    private String eventId;
    private String deviceId;
    private String tenantId;
    private Instant timestamp;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private Integer batteryLevel;
    private String officerId;
    private String vehicleId;
    private String status;
}
