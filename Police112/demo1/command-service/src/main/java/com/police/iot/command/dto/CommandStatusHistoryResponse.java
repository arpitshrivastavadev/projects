package com.police.iot.command.dto;

import com.police.iot.command.model.CommandStatus;
import lombok.Builder;

import java.time.Instant;

@Builder
public record CommandStatusHistoryResponse(
        CommandStatus fromStatus,
        CommandStatus toStatus,
        String reason,
        Instant changedAt) {
}
