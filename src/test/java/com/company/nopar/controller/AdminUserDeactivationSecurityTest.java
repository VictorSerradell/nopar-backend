package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con la cadena de seguridad real: desactivar y reactivar cuentas cuelga de /api/v1/admin, reservado a los
 * administradores, y además el servicio exige que quien lo pide sea super administrador (aquí se comprueba que su
 * negativa llega como un 403 y que el servicio recibe al usuario del token).
 */
@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AdminUserDeactivationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void givenNoToken_whenDeactivate_thenUnauthorizedAndNothingHappens() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/10/deactivate"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void givenAManagerOrADriver_whenDeactivate_thenForbiddenBeforeReachingTheService() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/10/deactivate").with(user(account(2L, User.Role.MANAGER, false))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/admin/users/10/deactivate").with(user(account(3L, User.Role.DRIVER, false))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    @Test
    void givenASuperAdmin_whenDeactivate_thenNoContentAndTheServiceReceivesHim() throws Exception {
        User boss = account(1L, User.Role.ADMIN, true);

        mockMvc.perform(patch("/api/v1/admin/users/10/deactivate").with(user(boss)))
                .andExpect(status().isNoContent());

        verify(userService).deactivateUser(10L, boss);
    }

    @Test
    void givenAnAdminThatIsNotSuperAdmin_whenTheServiceDenies_thenForbidden() throws Exception {
        User plainAdmin = account(4L, User.Role.ADMIN, false);
        doThrow(new AccessDeniedException("Solo un super administrador puede desactivar o reactivar cuentas"))
                .when(userService).deactivateUser(10L, plainAdmin);
        doThrow(new AccessDeniedException("Solo un super administrador puede desactivar o reactivar cuentas"))
                .when(userService).activateUser(10L, plainAdmin);

        mockMvc.perform(patch("/api/v1/admin/users/10/deactivate").with(user(plainAdmin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/admin/users/10/activate").with(user(plainAdmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenAnUnknownUser_whenDeactivate_thenNotFound() throws Exception {
        User boss = account(1L, User.Role.ADMIN, true);
        doThrow(new ResourceNotFoundException("Usuario no encontrado")).when(userService).deactivateUser(99L, boss);

        mockMvc.perform(patch("/api/v1/admin/users/99/deactivate").with(user(boss)))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenHisOwnAccount_whenDeactivate_thenBadRequestWithTheReason() throws Exception {
        User boss = account(1L, User.Role.ADMIN, true);
        doThrow(new InvalidRequestException("No puedes desactivar tu propia cuenta"))
                .when(userService).deactivateUser(1L, boss);

        mockMvc.perform(patch("/api/v1/admin/users/1/deactivate").with(user(boss)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No puedes desactivar tu propia cuenta"));
    }

    @Test
    void givenASuperAdmin_whenActivate_thenNoContentAndTheServiceReceivesHim() throws Exception {
        User boss = account(1L, User.Role.ADMIN, true);

        mockMvc.perform(patch("/api/v1/admin/users/10/activate").with(user(boss)))
                .andExpect(status().isNoContent());

        verify(userService).activateUser(10L, boss);
    }

    private static User account(Long id, User.Role role, boolean superAdmin) {
        User user = new User();
        user.setId(id);
        user.setUsername("usuario" + id);
        user.setPassword("no-importa");
        user.setRole(role);
        user.setSuperAdmin(superAdmin);
        return user;
    }
}
