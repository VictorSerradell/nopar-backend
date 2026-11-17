package com.company.nopar.controller;

import com.company.nopar.dto.AuthRequest;
import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.User;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.RefreshTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Spring Boot 4 registra el ObjectMapper de Jackson 3 (tools.jackson); el de com.fasterxml no es un bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthenticationManager authenticationManager;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void testLoginSuccess() throws Exception {
        AuthRequest request = new AuthRequest("conductor1", "password123");

        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("conductor1");
        mockUser.setRole(User.Role.DRIVER);

        RefreshToken mockRefreshToken = new RefreshToken(1L, "mock-refresh-token", Instant.now().plusSeconds(3600), mockUser);

        when(userRepository.findByUsername("conductor1")).thenReturn(Optional.of(mockUser));
        when(jwtService.generateToken(any(User.class))).thenReturn("mock-jwt-access-token");
        when(refreshTokenService.createRefreshToken("conductor1")).thenReturn(mockRefreshToken);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void testLoginBadCredentials() throws Exception {
        // Given: Un intento de login con contraseña incorrecta
        AuthRequest request = new AuthRequest("conductor1", "wrongpassword");

        // Simulamos que el AuthenticationManager rechaza las credenciales lanzando una excepción
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // When & Then: el ApiExceptionHandler traduce la BadCredentialsException a 401
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autorizado"));
    }

    @Test
    void testLoginUserNotFound() throws Exception {
        // Given: Un usuario que no existe en el sistema
        AuthRequest request = new AuthRequest("noexisto", "password123");

        // Simulamos que la autenticación pasa pero al buscar en el repositorio el usuario no aparece
        when(userRepository.findByUsername("noexisto")).thenReturn(Optional.empty());

        // When & Then: mismo 401 que con contraseña incorrecta, para no revelar si el usuario existe
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autorizado"));
    }
}