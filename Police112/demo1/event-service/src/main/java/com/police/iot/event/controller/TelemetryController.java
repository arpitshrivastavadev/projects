package com.police.iot.event.controller;

import com.police.iot.common.dto.PoliceTelemetry;
import com.police.iot.event.service.TelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TelemetryController {

    private final TelemetrySnapshotService telemetrySnapshotService;

    /**
     * Get telemetry for a specific device
     */
    @GetMapping("/device/{deviceId}")
    public PoliceTelemetry getDeviceTelemetry(@PathVariable String deviceId) {
        return telemetrySnapshotService.getDeviceTelemetry(deviceId);
    }

    /**
     * Get all active device telemetry (Digital Twin snapshots)
     */
    @GetMapping("/all")
    public List<PoliceTelemetry> getAllTelemetry() {
        return telemetrySnapshotService.getAllTelemetry();
    }

    /**
     * Get count of active devices
     */
    @GetMapping("/count")
    public long getActiveDeviceCount() {
        return telemetrySnapshotService.getAllTelemetry().size();
    }
}
