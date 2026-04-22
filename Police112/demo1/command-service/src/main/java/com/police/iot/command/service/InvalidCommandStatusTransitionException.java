package com.police.iot.command.service;

import com.police.iot.command.model.CommandStatus;

public class InvalidCommandStatusTransitionException extends RuntimeException {
    public InvalidCommandStatusTransitionException(CommandStatus from, CommandStatus to) {
        super("Invalid command status transition: " + from + " -> " + to);
    }
}
