package com.police.iot.command.service;

import com.police.iot.command.dto.CommandAckRequest;
import com.police.iot.command.dto.CommandResponse;
import com.police.iot.command.dto.CommandStatusHistoryResponse;
import com.police.iot.command.dto.CreateCommandRequest;
import com.police.iot.command.model.CommandEntity;
import com.police.iot.command.model.CommandStatus;
import com.police.iot.command.model.CommandStatusHistoryEntity;
import com.police.iot.command.repository.CommandRepository;
import com.police.iot.command.repository.CommandStatusHistoryRepository;
import com.police.iot.common.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandService {

    private static final Set<CommandStatus> ACK_ALLOWED_STATUSES = Set.of(CommandStatus.SENT, CommandStatus.CREATED);

    private final CommandRepository commandRepository;
    private final CommandStatusHistoryRepository statusHistoryRepository;
    private final MeterRegistry meterRegistry;

    @Transactional
    public CommandResponse createCommand(CreateCommandRequest request) {
        String tenantId = currentTenant();
        validateCreateRequest(request);

        CommandEntity command = new CommandEntity();
        command.setTenantId(tenantId);
        command.setTargetDeviceId(request.getTargetDeviceId());
        command.setCommandType(request.getCommandType());
        command.setPayload(request.getPayload());
        command.setStatus(CommandStatus.CREATED);

        CommandEntity saved = commandRepository.save(command);
        appendHistory(saved, null, CommandStatus.CREATED, "Command accepted by API");
        meterRegistry.counter("commands.created").increment();
        log.info("Command {} created for tenant={} targetDeviceId={} type={}",
                saved.getCommandId(), tenantId, saved.getTargetDeviceId(), saved.getCommandType());

        saved = transition(saved, CommandStatus.SENT, "Dispatch placeholder step completed");

        return toResponse(saved);
    }

    @Transactional
    public CommandResponse getCommand(String commandId) {
        CommandEntity command = findTenantCommand(commandId);
        return toResponse(command);
    }

    @Transactional
    public CommandResponse acknowledgeCommand(String commandId, CommandAckRequest request) {
        CommandEntity command = findTenantCommand(commandId);

        if (request == null || request.getStatus() == null) {
            throw new InvalidCommandStatusTransitionException(command.getStatus(), null);
        }

        CommandStatus requestedStatus = request.getStatus();
        if (requestedStatus != CommandStatus.ACKED
                && requestedStatus != CommandStatus.FAILED
                && requestedStatus != CommandStatus.TIMED_OUT) {
            throw new InvalidCommandStatusTransitionException(command.getStatus(), requestedStatus);
        }

        if (!ACK_ALLOWED_STATUSES.contains(command.getStatus())) {
            throw new InvalidCommandStatusTransitionException(command.getStatus(), requestedStatus);
        }

        CommandEntity updated = transition(command, requestedStatus, request.getReason());

        if (requestedStatus == CommandStatus.ACKED) {
            meterRegistry.counter("commands.acked").increment();
        } else if (requestedStatus == CommandStatus.FAILED) {
            meterRegistry.counter("commands.failed").increment();
        } else if (requestedStatus == CommandStatus.TIMED_OUT) {
            meterRegistry.counter("commands.timed_out").increment();
        }

        return toResponse(updated);
    }

    private CommandEntity transition(CommandEntity command, CommandStatus targetStatus, String reason) {
        CommandStatus from = command.getStatus();
        command.setStatus(targetStatus);
        CommandEntity saved = commandRepository.save(command);
        appendHistory(saved, from, targetStatus, reason);
        log.info("Command {} status transition {} -> {} for tenant={} reason={}",
                command.getCommandId(), from, targetStatus, command.getTenantId(), reason);
        return saved;
    }

    private CommandEntity findTenantCommand(String commandId) {
        return commandRepository.findByCommandIdAndTenantId(commandId, currentTenant())
                .orElseThrow(() -> new CommandNotFoundException(commandId));
    }

    private void appendHistory(CommandEntity command, CommandStatus fromStatus, CommandStatus toStatus, String reason) {
        CommandStatusHistoryEntity history = new CommandStatusHistoryEntity();
        history.setCommandId(command.getCommandId());
        history.setTenantId(command.getTenantId());
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setReason(reason);
        statusHistoryRepository.save(history);
    }

    private CommandResponse toResponse(CommandEntity command) {
        List<CommandStatusHistoryResponse> history = statusHistoryRepository
                .findByCommandIdAndTenantIdOrderByChangedAtAsc(command.getCommandId(), command.getTenantId())
                .stream()
                .map(h -> CommandStatusHistoryResponse.builder()
                        .fromStatus(h.getFromStatus())
                        .toStatus(h.getToStatus())
                        .reason(h.getReason())
                        .changedAt(h.getChangedAt())
                        .build())
                .toList();

        return CommandResponse.builder()
                .commandId(command.getCommandId())
                .tenantId(command.getTenantId())
                .targetDeviceId(command.getTargetDeviceId())
                .commandType(command.getCommandType())
                .payload(command.getPayload())
                .status(command.getStatus())
                .createdAt(command.getCreatedAt())
                .updatedAt(command.getUpdatedAt())
                .history(history)
                .build();
    }

    private void validateCreateRequest(CreateCommandRequest request) {
        if (request == null
                || isBlank(request.getTargetDeviceId())
                || isBlank(request.getCommandType())
                || isBlank(request.getPayload())) {
            throw new IllegalArgumentException("targetDeviceId, commandType and payload are required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String currentTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("TenantContext is empty");
        }
        return tenantId;
    }
}
