CREATE TABLE IF NOT EXISTS device_commands (
    command_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    target_device_id VARCHAR(120) NOT NULL,
    command_type VARCHAR(120) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_commands_tenant_command
    ON device_commands (tenant_id, command_id);

CREATE INDEX IF NOT EXISTS idx_device_commands_tenant_device
    ON device_commands (tenant_id, target_device_id);

CREATE TABLE IF NOT EXISTS command_status_history (
    history_id VARCHAR(36) PRIMARY KEY,
    command_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(250),
    changed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_command_status_history_command
        FOREIGN KEY (command_id) REFERENCES device_commands(command_id)
);

CREATE INDEX IF NOT EXISTS idx_cmd_status_history_tenant_command
    ON command_status_history (tenant_id, command_id);
