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
 * CORS con la cadena de seguridad real y orígenes configurados: el cliente web (otro origen que la API) puede
 * hacer el preflight sin token y llamar con la cabecera Authorization; cualquier otro origen queda fuera.
 */
@WebMvcTest(controllers = FleetController.class,
        properties = "application.security.cors.allowed-origins=https://app.empresa.com, http://localhost:*")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class CorsSecurityTest {

    private static final String WEB_APP = "https://app.empresa.com";

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
    void givenAllowedOrigin_whenPreflightWithoutToken_thenOkWithTheCorsHeaders() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options("/api/v1/fleet/vehicles/1/kilometers")
                        .header("Origin", WEB_APP)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(WEB_APP);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("PATCH").contains("DELETE");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("authorization");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
        // la sesión va en la cabecera Authorization, no en cookies
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void givenOriginMatchingAPattern_whenPreflight_thenAllowed() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options("/api/v1/fleet/vehicles")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4200");
    }

    @Test
    void givenUnknownOrigin_whenPreflight_thenForbiddenWithoutCorsHeaders() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(options("/api/v1/fleet/vehicles")
                        .header("Origin", "https://otra-web.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse();

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void givenMethodNotAllowed_whenPreflight_thenForbidden() throws Exception {
        // la API no usa PUT: el navegador no debe poder hacerlo ni desde el origen autorizado
        mockMvc.perform(options("/api/v1/fleet/vehicles/1")
                        .header("Origin", WEB_APP)
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAllowedOrigin_whenRequestWithoutToken_thenUnauthorizedButReadableByTheBrowser() throws Exception {
        // el 401 también lleva la cabecera CORS: sin ella el navegador oculta la respuesta y la web no sabe que debe
        // renovar el token
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/fleet/vehicles").header("Origin", WEB_APP))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(WEB_APP);
    }

    @Test
    void givenUnknownOrigin_whenRequest_thenForbiddenBeforeAnyAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles").header("Origin", "https://otra-web.example"))
                .andExpect(status().isForbidden());
    }
}
