package com.police.iot.device.repository;

import com.police.iot.common.model.Officer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface OfficerRepository extends JpaRepository<Officer, UUID> {
    List<Officer> findByTenantId(String tenantId);

    List<Officer> findByTenantIdAndBadgeNumber(String tenantId, String badgeNumber);
}
