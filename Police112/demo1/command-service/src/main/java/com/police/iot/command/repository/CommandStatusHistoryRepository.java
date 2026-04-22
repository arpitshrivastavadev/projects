package com.police.iot.command.repository;

import com.police.iot.command.model.CommandStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommandStatusHistoryRepository extends JpaRepository<CommandStatusHistoryEntity, String> {
    List<CommandStatusHistoryEntity> findByCommandIdAndTenantIdOrderByChangedAtAsc(String commandId, String tenantId);
}
