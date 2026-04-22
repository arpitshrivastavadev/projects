package com.police.iot.command.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCommandRequest {

    private String targetDeviceId;

    private String commandType;

    private String payload;
}
