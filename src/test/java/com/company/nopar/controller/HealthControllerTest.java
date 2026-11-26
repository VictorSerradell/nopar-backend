package com.company.nopar.controller;

import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Con la cadena de seguridad real: el healthcheck de Railway no lleva token. */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void givenNoToken_whenHealth_thenUpWithoutTouchingAnyUser() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        verifyNoInteractions(jwtService, userDetailsService);
    }

    @Test
    void givenNoToken_whenHeadHealth_thenOk() throws Exception {
        mockMvc.perform(head("/health")).andExpect(status().isOk());
    }

    @Test
    void whenAnyOtherPath_thenItStillRequiresAToken() throws Exception {
        // /health es una ruta exacta: no abre nada más
        mockMvc.perform(get("/health/otra")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/healthz")).andExpect(status().isUnauthorized());
    }
}
