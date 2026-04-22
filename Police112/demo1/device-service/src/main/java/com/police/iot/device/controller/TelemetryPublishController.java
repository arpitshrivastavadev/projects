package com.police.iot.device.controller;

import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.common.security.TenantContext;
import com.police.iot.device.service.TelemetryPublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryPublishController {

    private final TelemetryPublishService telemetryPublishService;

    @PostMapping
    public ResponseEntity<Void> publish(@RequestBody PoliceTelemetry telemetry) {
        if (telemetry.getEventId() == null || telemetry.getEventId().isBlank()) {
            telemetry.setEventId(UUID.randomUUID().toString());
        }
        if (telemetry.getTimestamp() == null) {
            telemetry.setTimestamp(Instant.now());
        }
        if (telemetry.getTenantId() == null || telemetry.getTenantId().isBlank()) {
            telemetry.setTenantId(TenantContext.getTenantId());
        }

        telemetryPublishService.publish(telemetry);
        return ResponseEntity.accepted().build();
    }
}
