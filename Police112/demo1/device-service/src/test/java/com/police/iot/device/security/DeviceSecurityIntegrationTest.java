package com.police.iot.device.security;

import com.police.iot.common.model.Officer;
import com.police.iot.common.security.AuthenticatedTenantResolver;
import com.police.iot.common.security.TenantFilter;
import com.police.iot.device.config.SecurityConfig;
import com.police.iot.device.controller.PoliceController;
import com.police.iot.device.repository.IncidentRepository;
import com.police.iot.device.repository.OfficerRepository;
import com.police.iot.device.repository.PatrolVehicleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PoliceController.class)
@Import({SecurityConfig.class, TenantFilter.class, AuthenticatedTenantResolver.class})
class DeviceSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OfficerRepository officerRepository;

    @MockBean
    private PatrolVehicleRepository patrolVehicleRepository;

    @MockBean
    private IncidentRepository incidentRepository;

    @Test
    void authenticatedRequestResolvesTenantFromPrincipalClaims() throws Exception {
        Officer officer = new Officer();
        officer.setName("Officer Jane");
        officer.setBadgeNumber("42");
        officer.setTenantId("tenant-a");

        when(officerRepository.findByTenantId("tenant-a")).thenReturn(List.of(officer));

        mockMvc.perform(get("/api/v1/police/officers")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isOk());

        verify(officerRepository).findByTenantId("tenant-a");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/police/officers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mismatchedTenantHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/police/officers")
                        .header(TenantFilter.TENANT_HEADER, "tenant-b")
                        .with(authentication(authWithTenant("tenant-a"))))
                .andExpect(status().isForbidden());

        verify(officerRepository, never()).findByTenantId(anyString());
    }

    private TestingAuthenticationToken authWithTenant(String tenantId) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(Map.of("tenant_id", tenantId), null, "ROLE_USER");
        authentication.setAuthenticated(true);
        return authentication;
    }
}
