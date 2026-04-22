package com.police.iot.command.model;

import com.police.iot.common.model.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "device_commands", indexes = {
        @Index(name = "idx_device_commands_tenant_command", columnList = "tenant_id,command_id", unique = true),
        @Index(name = "idx_device_commands_tenant_device", columnList = "tenant_id,target_device_id")
})
@Getter
@Setter
public class CommandEntity extends BaseEntity {

    @Id
    @Column(name = "command_id", nullable = false, updatable = false, length = 36)
    private String commandId;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "target_device_id", nullable = false, length = 120)
    private String targetDeviceId;

    @Column(name = "command_type", nullable = false, length = 120)
    private String commandType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommandStatus status;

    @PrePersist
    void ensureId() {
        if (commandId == null || commandId.isBlank()) {
            commandId = UUID.randomUUID().toString();
        }
    }
}
