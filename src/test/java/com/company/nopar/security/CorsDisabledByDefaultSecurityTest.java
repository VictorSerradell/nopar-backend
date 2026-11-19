package com.company.nopar.security;

import com.company.nopar.controller.FleetController;
import com.company.nopar.service.InspectionService;
import com.company.nopar.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sin orígenes configurados (el valor por defecto) la API no autoriza a ningún navegador de otro origen.
 * La propiedad se fija vacía a propósito: un CORS_ALLOWED_ORIGINS en el .env local no debe alterar este test.
 */
@WebMvcTest(controllers = FleetController.class, properties = "application.security.cors.allowed-origins=")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class CorsDisabledByDefaultSecurityTest {

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
    void givenNoAllowedOrigins_whenPreflight_thenNoCorsHeaders() throws Exception {
        // Sin configuración el filtro CORS contesta el preflight con un 200 vacío y sin cabeceras CORS: lo que
        // cuenta es que no hay Access-Control-Allow-*, con lo que el navegador no deja pasar la petición real
        MockHttpServletResponse response = mockMvc.perform(options("/api/v1/fleet/vehicles")
                        .header("Origin", "https://app.empresa.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andReturn().getResponse();

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isNull();
    }

    @Test
    void givenNoAllowedOrigins_whenRequestFromAnotherOrigin_thenNoCorsHeaders() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/fleet/vehicles").header("Origin", "https://app.empresa.com"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
