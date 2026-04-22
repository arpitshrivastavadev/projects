package com.police.iot.command.controller;

import com.police.iot.command.service.CommandNotFoundException;
import com.police.iot.command.service.InvalidCommandStatusTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class CommandExceptionHandler {

    @ExceptionHandler(CommandNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(CommandNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({InvalidCommandStatusTransitionException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleInvalidTransition(InvalidCommandStatusTransitionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }
}
