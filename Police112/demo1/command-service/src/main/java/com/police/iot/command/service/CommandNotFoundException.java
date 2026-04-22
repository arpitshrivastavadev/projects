package com.police.iot.command.service;

public class CommandNotFoundException extends RuntimeException {
    public CommandNotFoundException(String commandId) {
        super("Command not found for id=" + commandId);
    }
}
