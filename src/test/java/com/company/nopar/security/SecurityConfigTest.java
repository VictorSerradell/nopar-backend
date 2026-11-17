package com.company.nopar.security;

import com.company.nopar.controller.FleetController;
import com.company.nopar.service.VehicleInspectionService;
import com.company.nopar.service.VehicleService;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba la cadena de seguridad real (SecurityConfig + JwtAuthenticationFilter) sobre FleetController,
 * sin base de datos: 401 sin credenciales o con token inválido, 403 por rol insuficiente.
 */
@WebMvcTest(FleetController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private VehicleService vehicleService;

    @MockitoBean
    private VehicleInspectionService inspectionService;

    @Test
    void givenNoToken_whenProtectedEndpoint_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givenMalformedToken_whenProtectedEndpoint_thenUnauthorizedInsteadOfServerError() throws Exception {
        when(jwtService.extractUsername("garbage")).thenThrow(new MalformedJwtException("malformed"));

        mockMvc.perform(get("/api/v1/fleet/vehicles")
                        .header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void givenDriver_whenAdminOnlyMethod_thenForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void givenDriver_whenAdminUrl_thenForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/vehicles"))
                .andExpect(status().isForbidden());
    }
}
