package com.police.iot.command.repository;

import com.police.iot.command.model.CommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommandRepository extends JpaRepository<CommandEntity, String> {
    Optional<CommandEntity> findByCommandIdAndTenantId(String commandId, String tenantId);
}
