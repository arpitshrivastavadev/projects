package com.police.iot.device.repository;

import com.police.iot.device.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByTenantIdAndRouteAndHttpMethodAndIdempotencyKey(
            String tenantId,
            String route,
            String httpMethod,
            String idempotencyKey
    );
}
