package com.company.nopar.controller;

import com.company.nopar.dto.AuthRequest;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRefreshTokenException;
import com.company.nopar.exception.TooManyLoginAttemptsException;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.LoginRateLimiter;
import com.company.nopar.service.RefreshTokenService;
import com.company.nopar.service.RefreshTokenService.IssuedRefreshToken;
import com.company.nopar.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private JwtService jwtService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    // El cambio de contraseña necesita el usuario autenticado y se prueba con la cadena de seguridad real en
    // AuthEndpointsSecurityTest; aquí no hay filtros, así que solo hace falta el bean para que el contexto arranque
    @MockitoBean
    private UserService userService;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    void testLoginSuccess() throws Exception {
        AuthRequest request = new AuthRequest("conductor1", "password123");

        User mockUser = driver();

        when(userRepository.findByUsername("conductor1")).thenReturn(Optional.of(mockUser));
        when(jwtService.generateToken(any(User.class))).thenReturn("mock-jwt-access-token");
        when(refreshTokenService.createRefreshToken(any(User.class)))
                .thenReturn(new IssuedRefreshToken("mock-refresh-token", mockUser));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock-jwt-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("mock-refresh-token"));
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
    void givenABadPassword_whenLogin_thenTheFailureIsCounted() throws Exception {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("conductor1", "mala"))))
                .andExpect(status().isUnauthorized());

        verify(loginRateLimiter).recordFailure(org.mockito.ArgumentMatchers.eq("conductor1"), any());
        verify(loginRateLimiter, never()).recordSuccess(any(), any());
    }

    @Test
    void givenAValidLogin_whenLogin_thenTheCounterOfThatUserIsCleared() throws Exception {
        when(userRepository.findByUsername("conductor1")).thenReturn(Optional.of(driver()));
        when(jwtService.generateToken(any(User.class))).thenReturn("access");
        when(refreshTokenService.createRefreshToken(any(User.class)))
                .thenReturn(new IssuedRefreshToken("refresh", driver()));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("conductor1", "password123"))))
                .andExpect(status().isOk());

        verify(loginRateLimiter).recordSuccess(org.mockito.ArgumentMatchers.eq("conductor1"), any());
        verify(loginRateLimiter, never()).recordFailure(any(), any());
    }

    @Test
    void givenTooManyFailedAttempts_whenLogin_thenTooManyRequestsWithRetryAfterAndNobodyIsAuthenticated() throws Exception {
        doThrow(new TooManyLoginAttemptsException(Duration.ofSeconds(125)))
                .when(loginRateLimiter).checkAllowed(org.mockito.ArgumentMatchers.eq("conductor1"), any());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("conductor1", "password123"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "125"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Demasiados intentos"))
                .andExpect(jsonPath("$.detail").value("Demasiados intentos fallidos. Vuelve a intentarlo en 3 minutos."));

        // Bloqueado: ni siquiera se comprueba la contraseña (así tampoco se puede seguir adivinando)
        verifyNoInteractions(authenticationManager);
        verify(loginRateLimiter, never()).recordFailure(any(), any());
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

    @Test
    void givenValidRefreshToken_whenRefresh_thenReturnsNewAccessAndRefreshTokens() throws Exception {
        when(refreshTokenService.rotateRefreshToken("old-refresh-token"))
                .thenReturn(new IssuedRefreshToken("new-refresh-token", driver()));
        when(jwtService.generateToken(any(User.class))).thenReturn("new-access-token");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "old-refresh-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
    }

    @Test
    void givenInvalidRefreshToken_whenRefresh_thenUnauthorizedInsteadOfServerError() throws Exception {
        when(refreshTokenService.rotateRefreshToken("caducado"))
                .thenThrow(new InvalidRefreshTokenException("Refresh token no válido o expirado"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "caducado"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autorizado"))
                .andExpect(jsonPath("$.detail").value("Refresh token no válido o expirado"));
    }

    @Test
    void givenBlankRefreshToken_whenRefresh_thenBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.refreshToken").value("El refresh token es obligatorio"));

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void givenRefreshToken_whenLogout_thenNoContentAndOnlyThatTokenIsRevoked() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "token-del-movil"}
                                """))
                .andExpect(status().isNoContent());

        verify(refreshTokenService).revokeRefreshToken("token-del-movil");
        verify(refreshTokenService, never()).deleteByUser(any());
    }

    @Test
    void givenBlankRefreshToken_whenLogout_thenBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.refreshToken").value("El refresh token es obligatorio"));

        verifyNoInteractions(refreshTokenService);
    }

    private static User driver() {
        User user = new User();
        user.setId(1L);
        user.setUsername("conductor1");
        user.setRole(User.Role.DRIVER);
        return user;
    }
}
