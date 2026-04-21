package com.police.iot.device.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.police.iot.device.repository.IdempotencyRecordRepository;
import com.police.iot.device.repository.OfficerRepository;
import com.police.iot.device.repository.PatrolVehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:device-idempotency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=true"
})
class DeviceWriteIdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OfficerRepository officerRepository;

    @Autowired
    private PatrolVehicleRepository patrolVehicleRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        idempotencyRecordRepository.deleteAll();
        officerRepository.deleteAll();
        patrolVehicleRepository.deleteAll();
    }

    @Test
    void firstPostOfficerSucceedsAndStoresIdempotencyRecord() throws Exception {
        String body = """
                {
                  "userId": "user-1",
                  "name": "Officer Jane",
                  "badgeNumber": "B-1001",
                  "status": "ACTIVE"
                }
                """;

        mockMvc.perform(post("/api/v1/police/officers")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "idem-officer-1")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"));

        assertThat(officerRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
    }

    @Test
    void repeatedSameRequestWithSameKeyReturnsSameResultWithoutDuplicateOfficer() throws Exception {
        String body = """
                {
                  "userId": "user-2",
                  "name": "Officer Mike",
                  "badgeNumber": "B-1002",
                  "status": "ACTIVE"
                }
                """;

        MvcResult firstResult = mockMvc.perform(post("/api/v1/police/officers")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "idem-officer-2")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult secondResult = mockMvc.perform(post("/api/v1/police/officers")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "idem-officer-2")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(secondResult.getResponse().getContentAsString());

        assertThat(secondJson.get("id").asText()).isEqualTo(firstJson.get("id").asText());
        assertThat(officerRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        String firstBody = """
                {
                  "plateNumber": "PLATE-101",
                  "vehicleType": "SUV",
                  "edgeNodeId": "edge-1",
                  "status": "ACTIVE"
                }
                """;

        String secondBody = """
                {
                  "plateNumber": "PLATE-102",
                  "vehicleType": "BIKE",
                  "edgeNodeId": "edge-2",
                  "status": "ACTIVE"
                }
                """;

        mockMvc.perform(post("/api/v1/police/vehicles")
                        .contentType(APPLICATION_JSON)
                        .content(firstBody)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "idem-vehicle-1")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/police/vehicles")
                        .contentType(APPLICATION_JSON)
                        .content(secondBody)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "idem-vehicle-1")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isConflict());

        assertThat(patrolVehicleRepository.count()).isEqualTo(1L);
    }

    @Test
    void sameKeyAcrossDifferentTenantsDoesNotCollide() throws Exception {
        String tenantABody = """
                {
                  "userId": "user-4",
                  "name": "Officer A",
                  "badgeNumber": "B-2001",
                  "status": "ACTIVE"
                }
                """;

        String tenantBBody = """
                {
                  "userId": "user-5",
                  "name": "Officer B",
                  "badgeNumber": "B-2002",
                  "status": "ACTIVE"
                }
                """;

        mockMvc.perform(post("/api/v1/police/officers")
                        .contentType(APPLICATION_JSON)
                        .content(tenantABody)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "shared-key")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/police/officers")
                        .contentType(APPLICATION_JSON)
                        .content(tenantBBody)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "shared-key")
                        .with(authentication(authWithTenant("tenant-b"))))
                .andExpect(status().isOk());

        assertThat(officerRepository.count()).isEqualTo(2L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(2L);
    }

    @Test
    void unauthenticatedRequestStillRejectedBySecurity() throws Exception {
        String body = """
                {
                  "userId": "user-6",
                  "name": "Officer NoAuth",
                  "badgeNumber": "B-3001",
                  "status": "ACTIVE"
                }
                """;

        mockMvc.perform(post("/api/v1/police/officers")
                        .contentType(APPLICATION_JSON)
                        .content(body)
                        .header(IdempotencyAspect.IDEMPOTENCY_HEADER, "idem-no-auth"))
                .andExpect(status().isUnauthorized());
    }

    private TestingAuthenticationToken authWithTenant(String tenantId) {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken(Map.of("tenant_id", tenantId), null, "ROLE_USER");
        authentication.setAuthenticated(true);
        return authentication;
    }
}
