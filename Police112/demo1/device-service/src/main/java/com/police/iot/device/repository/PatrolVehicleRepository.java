package com.police.iot.device.repository;

import com.police.iot.common.model.PatrolVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PatrolVehicleRepository extends JpaRepository<PatrolVehicle, UUID> {
    List<PatrolVehicle> findByTenantId(String tenantId);

    List<PatrolVehicle> findByTenantIdAndPlateNumber(String tenantId, String plateNumber);
}
