package com.police.iot.device.controller;

import com.police.iot.common.model.Incident;
import com.police.iot.common.model.Officer;
import com.police.iot.common.model.PatrolVehicle;
import com.police.iot.common.security.TenantContext;
import com.police.iot.device.repository.IncidentRepository;
import com.police.iot.device.repository.OfficerRepository;
import com.police.iot.device.repository.PatrolVehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/police")
@CrossOrigin(
        originPatterns = "*",
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS}
)
@RequiredArgsConstructor
public class PoliceController {

    private final OfficerRepository officerRepository;
    private final PatrolVehicleRepository vehicleRepository;
    private final IncidentRepository incidentRepository;

    @GetMapping("/officers")
    public List<Officer> getAllOfficers() {
        return officerRepository.findByTenantId(TenantContext.getTenantId());
    }

    @PostMapping("/officers")
    public Officer createOfficer(@RequestBody Officer officer) {
        officer.setTenantId(TenantContext.getTenantId());
        return officerRepository.save(officer);
    }

    @GetMapping("/vehicles")
    public List<PatrolVehicle> getAllVehicles() {
        return vehicleRepository.findByTenantId(TenantContext.getTenantId());
    }

    @PostMapping("/vehicles")
    public PatrolVehicle createVehicle(@RequestBody PatrolVehicle vehicle) {
        vehicle.setTenantId(TenantContext.getTenantId());
        return vehicleRepository.save(vehicle);
    }

    @GetMapping("/incidents")
    public List<Incident> getAllIncidents() {
        return incidentRepository.findByTenantId(TenantContext.getTenantId());
    }
}
