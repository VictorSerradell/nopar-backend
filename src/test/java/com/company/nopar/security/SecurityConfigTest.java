package com.company.nopar.security;

import com.company.nopar.controller.FleetController;
import com.company.nopar.service.InspectionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private InspectionService inspectionService;

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
        // Cuerpo válido a propósito: la validación del @RequestBody se ejecuta antes que @PreAuthorize,
        // así que con un cuerpo inválido la respuesta sería 400 y no se estaría probando el rol.
        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"licensePlate": "1234-ABC", "model": "Ford Transit", "currentKilometers": 1000}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenNoToken_whenFormerPublicRegisterUrl_thenUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "intruso", "password": "password-segura-123"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void givenDriver_whenCreateUser_thenForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "otro-conductor", "password": "password-segura-123"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void givenDriver_whenAdminUrl_thenForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inspections"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    void givenDriver_whenChangeVehicleStatus_thenForbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/vehicles/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "AVAILABLE"}
                                """))
                .andExpect(status().isForbidden());
    }
}
