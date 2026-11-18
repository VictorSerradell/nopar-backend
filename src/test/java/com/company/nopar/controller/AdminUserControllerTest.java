package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
}
