package com.company.nopar.controller;

import com.company.nopar.entity.DeviceToken;
import com.company.nopar.entity.User;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con la cadena de seguridad real: el dispositivo se registra para el usuario del token, los dos roles pueden
 * hacerlo y nadie sin token.
 */
@WebMvcTest(DeviceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DeviceControllerTest {

    private static final String URL = "/api/v1/devices";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void givenNoToken_whenRegisterOrUnregister_thenUnauthorized() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "token-abc", "platform": "ANDROID"}
                        """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL).param("token", "token-abc"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(deviceService);
    }

    @Test
    void givenADriver_whenRegisterDevice_thenNoContentAndItIsRegisteredForThatDriver() throws Exception {
        mockMvc.perform(post(URL).with(user(newUser(2L, "driver1", User.Role.DRIVER)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"token": "token-abc", "platform": "ANDROID"}
                                """))
                .andExpect(status().isNoContent());

        verify(deviceService).registerDevice(
                argThat(u -> u != null && "driver1".equals(u.getUsername())), eq("token-abc"), eq(DeviceToken.Platform.ANDROID));
    }

    @Test
    void givenAnAdmin_whenRegisterDevice_thenNoContent() throws Exception {
        mockMvc.perform(post(URL).with(user(newUser(1L, "admin1", User.Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"token": "token-web", "platform": "WEB"}
                                """))
                .andExpect(status().isNoContent());

        verify(deviceService).registerDevice(
                argThat(u -> u != null && "admin1".equals(u.getUsername())), eq("token-web"), eq(DeviceToken.Platform.WEB));
    }

    @Test
    void givenEmptyBody_whenRegisterDevice_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).with(user(newUser(2L, "driver1", User.Role.DRIVER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.token").value("El token del dispositivo es obligatorio"))
                .andExpect(jsonPath("$.errors.platform").value("La plataforma es obligatoria"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void givenATokenThatIsTooLong_whenRegisterDevice_thenBadRequest() throws Exception {
        String tooLong = "x".repeat(513);

        mockMvc.perform(post(URL).with(user(newUser(2L, "driver1", User.Role.DRIVER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"" + tooLong + "\", \"platform\": \"IOS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").value("El token del dispositivo no puede superar los 512 caracteres"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void givenAnUnknownPlatform_whenRegisterDevice_thenBadRequest() throws Exception {
        mockMvc.perform(post(URL).with(user(newUser(2L, "driver1", User.Role.DRIVER)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"token": "token-abc", "platform": "DESKTOP"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void givenAToken_whenUnregisterDevice_thenNoContentAndTheUserOfTheTokenIsPassedOn() throws Exception {
        mockMvc.perform(delete(URL).param("token", "token-abc").with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isNoContent());

        verify(deviceService).unregisterDevice(argThat(u -> u != null && "driver1".equals(u.getUsername())), eq("token-abc"));
    }

    @Test
    void givenNoTokenParameter_whenUnregisterDevice_thenBadRequest() throws Exception {
        mockMvc.perform(delete(URL).with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(URL).param("token", "  ").with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"));

        verifyNoInteractions(deviceService);
    }

    private static User newUser(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return user;
    }
}
