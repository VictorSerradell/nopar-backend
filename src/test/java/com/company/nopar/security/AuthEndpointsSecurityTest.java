package com.company.nopar.security;

import com.company.nopar.controller.AuthController;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.RefreshTokenService;
import com.company.nopar.service.RefreshTokenService.IssuedRefreshToken;
import com.company.nopar.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba con la cadena de seguridad real quién puede cerrar sesión: /logout (este dispositivo) es público
 * porque la credencial es el refresh token, y /logout-all (todos los dispositivos) exige estar autenticado.
 * El cambio de contraseña también exige estar autenticado: es el usuario del token quien cambia SU contraseña.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AuthEndpointsSecurityTest {

    private static final String PASSWORD_CHANGE = """
            {"currentPassword": "clave-vieja-123", "newPassword": "clave-nueva-456"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private UserService userService;

    @Test
    void givenNoAccessToken_whenLogout_thenNoContentAndTheRefreshTokenIsRevoked() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "token-del-movil"}
                                """))
                .andExpect(status().isNoContent());

        verify(refreshTokenService).revokeRefreshToken("token-del-movil");
    }

    @Test
    void givenExpiredAccessToken_whenLogout_thenStillNoContent() throws Exception {
        // El access token dura 15 minutos: haber caducado no puede impedir cerrar la sesión
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer access-token-caducado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "token-del-movil"}
                                """))
                .andExpect(status().isNoContent());

        verify(refreshTokenService).revokeRefreshToken("token-del-movil");
        verifyNoInteractions(jwtService);
    }

    @Test
    void givenNoAccessToken_whenLogoutAll_thenUnauthorizedAndNothingRevoked() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void givenAuthenticatedUser_whenLogoutAll_thenNoContentAndAllHisRefreshTokensAreRevoked() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all").with(user(driver())))
                .andExpect(status().isNoContent());

        verify(refreshTokenService).deleteByUser("conductor1");
    }

    @Test
    void givenNoAccessToken_whenChangePassword_thenUnauthorizedAndNothingChanges() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PASSWORD_CHANGE))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService, refreshTokenService);
    }

    @Test
    void givenAuthenticatedUser_whenChangePassword_thenOkWithAFreshPairOfTokens() throws Exception {
        when(userService.changePassword(1L, "clave-vieja-123", "clave-nueva-456")).thenReturn(driver());
        when(jwtService.generateToken(any(User.class))).thenReturn("access-nuevo");
        when(refreshTokenService.createRefreshToken(any(User.class)))
                .thenReturn(new IssuedRefreshToken("refresh-nuevo", driver()));

        mockMvc.perform(patch("/api/v1/auth/password")
                        .with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PASSWORD_CHANGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-nuevo"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-nuevo"));

        // El id sale del usuario del token, no del cuerpo: nadie puede cambiar la contraseña de otro
        verify(userService).changePassword(1L, "clave-vieja-123", "clave-nueva-456");
    }

    @Test
    void givenAWrongCurrentPassword_whenChangePassword_thenBadRequestInsteadOfUnauthorizedAndNoTokensAreIssued() throws Exception {
        when(userService.changePassword(1L, "clave-vieja-123", "clave-nueva-456"))
                .thenThrow(new InvalidRequestException("La contraseña actual no es correcta"));

        // 400 y no 401: el usuario sigue autenticado, y un 401 haría que el cliente intentara renovar o cerrar la sesión
        mockMvc.perform(patch("/api/v1/auth/password")
                        .with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PASSWORD_CHANGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.detail").value("La contraseña actual no es correcta"));

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void givenBlankCurrentAndShortNewPassword_whenChangePassword_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password")
                        .with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "  ", "newPassword": "corta"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.currentPassword").value("La contraseña actual es obligatoria"))
                .andExpect(jsonPath("$.errors.newPassword").value("La nueva contraseña debe tener entre 8 y 72 caracteres"));

        verifyNoInteractions(userService, refreshTokenService);
    }

    @Test
    void givenANewPasswordOver72Characters_whenChangePassword_thenBadRequest() throws Exception {
        String tooLong = "a".repeat(73);

        mockMvc.perform(patch("/api/v1/auth/password")
                        .with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"clave-vieja-123\", \"newPassword\": \"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").value("La nueva contraseña debe tener entre 8 y 72 caracteres"));

        verifyNoInteractions(userService);
    }

    private static User driver() {
        User user = new User();
        user.setId(1L);
        user.setUsername("conductor1");
        user.setPassword("no-importa");
        user.setRole(User.Role.DRIVER);
        return user;
    }
}
