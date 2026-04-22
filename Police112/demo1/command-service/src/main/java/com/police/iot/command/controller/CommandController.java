package com.police.iot.command.controller;

import com.police.iot.command.dto.CommandAckRequest;
import com.police.iot.command.dto.CommandResponse;
import com.police.iot.command.dto.CreateCommandRequest;
import com.police.iot.command.service.CommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/commands")
@RequiredArgsConstructor
public class CommandController {

    private final CommandService commandService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommandResponse create(@RequestBody CreateCommandRequest request) {
        return commandService.createCommand(request);
    }

    @GetMapping("/{id}")
    public CommandResponse getById(@PathVariable("id") String commandId) {
        return commandService.getCommand(commandId);
    }

    @PostMapping("/{id}/acks")
    public CommandResponse acknowledge(@PathVariable("id") String commandId,
                                       @RequestBody CommandAckRequest request) {
        return commandService.acknowledgeCommand(commandId, request);
    }
}
