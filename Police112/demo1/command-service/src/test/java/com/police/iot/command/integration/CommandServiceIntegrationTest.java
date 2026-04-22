package com.police.iot.command.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.command.CommandServiceApplication;
import com.police.iot.command.config.CommandTenantFilter;
import com.police.iot.command.model.CommandStatus;
import com.police.iot.command.testutil.JwtTestTokenFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CommandServiceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.security.jwt.dev.enabled=true",
        "app.security.jwt.dev.issuer=test-command-service",
        "app.security.jwt.dev.secret=test-command-service-secret-1234567890",
        "spring.datasource.url=jdbc:h2:mem:commanddb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class CommandServiceIntegrationTest {

    private static final String ISSUER = "test-command-service";
    private static final String SECRET = "test-command-service-secret-1234567890";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createCommandSuccessfully() throws Exception {
        mockMvc.perform(post("/api/v1/commands")
                        .header("Authorization", "Bearer " + tokenForTenant("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetDeviceId":"vehicle-101",
                                  "commandType":"SIREN_ON",
                                  "payload":"{\\"duration\\":10}"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.targetDeviceId").value("vehicle-101"))
                .andExpect(jsonPath("$.status").value(CommandStatus.SENT.name()))
                .andExpect(jsonPath("$.history.length()").value(2));
    }

    @Test
    void fetchCommandById() throws Exception {
        String commandId = createCommandAndGetId("tenant-a");

        mockMvc.perform(get("/api/v1/commands/{id}", commandId)
                        .header("Authorization", "Bearer " + tokenForTenant("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(commandId))
                .andExpect(jsonPath("$.status").value(CommandStatus.SENT.name()));
    }

    @Test
    void tenantIsolationOnCommandLookup() throws Exception {
        String commandId = createCommandAndGetId("tenant-a");

        mockMvc.perform(get("/api/v1/commands/{id}", commandId)
                        .header("Authorization", "Bearer " + tokenForTenant("tenant-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusTransitionToAcked() throws Exception {
        String commandId = createCommandAndGetId("tenant-a");

        mockMvc.perform(post("/api/v1/commands/{id}/acks", commandId)
                        .header("Authorization", "Bearer " + tokenForTenant("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status":"ACKED",
                                  "reason":"Device confirmed command execution"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(CommandStatus.ACKED.name()))
                .andExpect(jsonPath("$.history.length()").value(3));
    }

    @Test
    void invalidTransitionReturnsBadRequest() throws Exception {
        String commandId = createCommandAndGetId("tenant-a");

        mockMvc.perform(post("/api/v1/commands/{id}/acks", commandId)
                        .header("Authorization", "Bearer " + tokenForTenant("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status":"SENT",
                                  "reason":"invalid"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/commands/abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mismatchedHeaderVsAuthenticatedTenantRejected() throws Exception {
        mockMvc.perform(get("/api/v1/commands/abc")
                        .header(CommandTenantFilter.TENANT_HEADER, "tenant-b")
                        .header("Authorization", "Bearer " + tokenForTenant("tenant-a")))
                .andExpect(status().isForbidden());
    }

    private String createCommandAndGetId(String tenantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/commands")
                        .header("Authorization", "Bearer " + tokenForTenant(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetDeviceId":"vehicle-222",
                                  "commandType":"LOCK_DOORS",
                                  "payload":"{\\"priority\\":\"high\"}"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        String commandId = root.path("commandId").asText();
        assertThat(commandId).isNotBlank();
        return commandId;
    }

    private String tokenForTenant(String tenantId) {
        return JwtTestTokenFactory.createHs256Token(ISSUER, SECRET, Map.of("tenant_id", tenantId));
    }
}
