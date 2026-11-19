package com.company.nopar.controller;

import com.company.nopar.dto.BoardAnnouncementResponse;
import com.company.nopar.entity.User;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con la cadena de seguridad real: quien lee el tablón y marca los avisos sale del token, y los dos roles
 * (conductor y administrador) pueden hacerlo, pero nadie sin token.
 */
@WebMvcTest(AnnouncementController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AnnouncementControllerTest {

    private static final String URL = "/api/v1/announcements";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnnouncementService announcementService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void givenNoToken_whenGetBoard_thenUnauthorized() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenADriver_whenGetBoard_thenSeesThePageWithTheReadFlagOfThatDriver() throws Exception {
        when(announcementService.getBoard(argThat(u -> u != null && Long.valueOf(2L).equals(u.getId())), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(item(1L, true, true), item(2L, false, false)), PageRequest.of(0, 20), 2));

        mockMvc.perform(get(URL).with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].pinned").value(true))
                .andExpect(jsonPath("$.content[0].read").value(true))
                .andExpect(jsonPath("$.content[0].createdByUsername").value("admin1"))
                .andExpect(jsonPath("$.content[0].createdAt", startsWith("2026-11-19T09:00")))
                .andExpect(jsonPath("$.content[1].read").value(false))
                .andExpect(jsonPath("$.content[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenPageAndSize_whenGetBoard_thenTheServiceReceivesThem() throws Exception {
        when(announcementService.getBoard(argThat(u -> u != null), eq(1), eq(5)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 6));

        mockMvc.perform(get(URL).param("page", "1").param("size", "5")
                        .with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void givenAnAdmin_whenGetBoard_thenOkToo() throws Exception {
        when(announcementService.getBoard(argThat(u -> u != null), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL).with(user(newUser(1L, "admin1", User.Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void givenInvalidPageOrSize_whenGetBoard_thenBadRequestWithoutCallingTheService() throws Exception {
        User driver = newUser(2L, "driver1", User.Role.DRIVER);

        mockMvc.perform(get(URL).param("page", "-1").with(user(driver)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"));
        mockMvc.perform(get(URL).param("size", "0").with(user(driver)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("size", "101").with(user(driver)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("size", "muchos").with(user(driver)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El parámetro 'size' no tiene un valor válido"));

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenNoToken_whenMarkAsRead_thenUnauthorized() throws Exception {
        mockMvc.perform(post(URL + "/5/read"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenADriver_whenMarkAsRead_thenNoContentAndTheServiceReceivesTheDriverOfTheToken() throws Exception {
        mockMvc.perform(post(URL + "/5/read").with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isNoContent());

        verify(announcementService).markAsRead(eq(5L), argThat(u -> u != null && "driver1".equals(u.getUsername())));
    }

    @Test
    void givenAnAdmin_whenMarkAsRead_thenNoContentToo() throws Exception {
        mockMvc.perform(post(URL + "/5/read").with(user(newUser(1L, "admin1", User.Role.ADMIN))))
                .andExpect(status().isNoContent());

        verify(announcementService).markAsRead(eq(5L), argThat(u -> u != null && "admin1".equals(u.getUsername())));
    }

    @Test
    void givenUnknownAnnouncement_whenMarkAsRead_thenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Aviso no encontrado con ID: 9"))
                .when(announcementService).markAsRead(eq(9L), argThat(u -> u != null));

        mockMvc.perform(post(URL + "/9/read").with(user(newUser(2L, "driver1", User.Role.DRIVER))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    private static BoardAnnouncementResponse item(Long id, boolean pinned, boolean read) {
        return new BoardAnnouncementResponse(id, "Aviso " + id, "Texto " + id, pinned, "admin1",
                LocalDateTime.of(2026, 11, 19, 9, 0), LocalDateTime.of(2026, 11, 19, 9, 0), read);
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
