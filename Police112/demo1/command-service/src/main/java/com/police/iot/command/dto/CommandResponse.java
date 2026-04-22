package com.police.iot.command.dto;

import com.police.iot.command.model.CommandStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder
public record CommandResponse(
        String commandId,
        String tenantId,
        String targetDeviceId,
        String commandType,
        String payload,
        CommandStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<CommandStatusHistoryResponse> history) {
}
