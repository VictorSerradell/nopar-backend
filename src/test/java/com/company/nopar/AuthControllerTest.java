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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private RefreshTokenService refreshTokenService;

    @MockBean
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

        // When & Then: Verificamos que Spring maneja la excepción (o que el controlador propaga el fallo)
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized()); // O isForbidden / isInternalServerError dependiendo de cómo gestione Spring Security el error por defecto
    }

    @Test
    void testLoginUserNotFound() throws Exception {
        // Given: Un usuario que no existe en el sistema
        AuthRequest request = new AuthRequest("noexisto", "password123");

        // Simulamos que la autenticación pasa pero al buscar en el repositorio el usuario no aparece
        when(userRepository.findByUsername("noexisto")).thenReturn(Optional.empty());

        // When & Then: Esperamos una respuesta de error (por ejemplo, 500 o 400 según el manejo de excepciones)
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError()); // Captura la IllegalArgumentException lanzada en el orElseThrow
    }
}