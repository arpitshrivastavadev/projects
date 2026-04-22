package com.police.iot.command.dto;

import com.police.iot.command.model.CommandStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommandAckRequest {

    private CommandStatus status;

    private String reason;
}
