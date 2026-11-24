package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminUserControllerTest {

    private static final String VALID_BODY = """
            {"username": "conductor2", "password": "password-segura-123"}
            """;

    private static final String INVALID_BODY = """
            {"username": "ab", "password": "corta"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenCreateDriver_thenCreatedWithoutPasswordInTheResponse() throws Exception {
        User created = new User();
        created.setId(10L);
        created.setUsername("conductor2");
        created.setPassword("$2a$10$hash-que-no-debe-salir");
        created.setRole(User.Role.DRIVER);
        when(userService.createDriver("conductor2", "password-segura-123")).thenReturn(created);

        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.username").value("conductor2"))
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenValidRequest_whenCreateManager_thenCreatedWithTheManagerRole() throws Exception {
        User created = new User();
        created.setId(11L);
        created.setUsername("conductor2");
        created.setPassword("$2a$10$hash-que-no-debe-salir");
        created.setRole(User.Role.MANAGER);
        when(userService.createManager("conductor2", "password-segura-123")).thenReturn(created);

        mockMvc.perform(post("/api/v1/admin/users/managers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenShortUsernameAndPassword_whenCreateManager_thenBadRequestAndNothingCreated() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/managers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void givenShortUsernameAndPassword_whenCreateDriver_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void givenExistingUsername_whenCreateDriver_thenConflict() throws Exception {
        when(userService.createDriver("conductor2", "password-segura-123"))
                .thenThrow(new DuplicateResourceException("Ya existe un usuario con el nombre: conductor2"));

        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void givenValidPassword_whenResetDriverPassword_thenNoContent() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/10/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "clave-nueva-456"}
                                """))
                .andExpect(status().isNoContent());

        verify(userService).resetDriverPassword(10L, "clave-nueva-456");
    }

    @Test
    void givenShortPassword_whenResetDriverPassword_thenBadRequestWithFieldError() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/10/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "corta"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").value("La nueva contraseña debe tener entre 8 y 72 caracteres"));

        verifyNoInteractions(userService);
    }

    @Test
    void givenUnknownUser_whenResetDriverPassword_thenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Usuario no encontrado"))
                .when(userService).resetDriverPassword(99L, "clave-nueva-456");

        mockMvc.perform(patch("/api/v1/admin/users/99/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "clave-nueva-456"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void givenAnAdminAsTarget_whenResetDriverPassword_thenBadRequest() throws Exception {
        doThrow(new InvalidRequestException("Solo se puede restablecer la contraseña de un conductor"))
                .when(userService).resetDriverPassword(1L, "clave-nueva-456");

        mockMvc.perform(patch("/api/v1/admin/users/1/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "clave-nueva-456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Solo se puede restablecer la contraseña de un conductor"));
    }
}
