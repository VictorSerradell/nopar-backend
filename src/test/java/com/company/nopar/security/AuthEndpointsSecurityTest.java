package com.company.nopar.security;

import com.company.nopar.controller.AuthController;
import com.company.nopar.entity.User;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba con la cadena de seguridad real quién puede cerrar sesión: /logout (este dispositivo) es público
 * porque la credencial es el refresh token, y /logout-all (todos los dispositivos) exige estar autenticado.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AuthEndpointsSecurityTest {

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

    private static User driver() {
        User user = new User();
        user.setId(1L);
        user.setUsername("conductor1");
        user.setPassword("no-importa");
        user.setRole(User.Role.DRIVER);
        return user;
    }
}
