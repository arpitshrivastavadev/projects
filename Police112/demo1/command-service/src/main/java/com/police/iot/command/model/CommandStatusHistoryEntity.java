package com.police.iot.command.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "command_status_history", indexes = {
        @Index(name = "idx_cmd_status_history_tenant_command", columnList = "tenant_id,command_id")
})
@Getter
@Setter
public class CommandStatusHistoryEntity {

    @Id
    @Column(name = "history_id", nullable = false, updatable = false, length = 36)
    private String historyId;

    @Column(name = "command_id", nullable = false, length = 36)
    private String commandId;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private CommandStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private CommandStatus toStatus;

    @Column(name = "reason", length = 250)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @PrePersist
    void beforeInsert() {
        if (historyId == null || historyId.isBlank()) {
            historyId = UUID.randomUUID().toString();
        }
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }
}
