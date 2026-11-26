package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.WaveChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Los mensajes sin leer por HTTP: quién pregunta y cómo se contesta. Con la cadena de seguridad real. */
@WebMvcTest(ChatUnreadController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ChatUnreadControllerTest {

    private static final String UNREAD = "/api/v1/chat/unread";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaveChatService chatService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void givenNoToken_whenGetUnread_thenUnauthorized() throws Exception {
        mockMvc.perform(get(UNREAD)).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenUnreadMessages_whenGetUnread_thenOneEntryPerWaveForTheUserOfTheToken() throws Exception {
        Map<Long, Long> unread = new LinkedHashMap<>();
        unread.put(1L, 3L);
        unread.put(2L, 7L);
        when(chatService.getUnread(argThat(u -> Long.valueOf(2L).equals(u.getId())))).thenReturn(unread);

        mockMvc.perform(get(UNREAD).with(user(newUser(2L, "ana", User.Role.DRIVER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].waveId").value(1))
                .andExpect(jsonPath("$[0].unread").value(3))
                .andExpect(jsonPath("$[1].waveId").value(2))
                .andExpect(jsonPath("$[1].unread").value(7));

        verify(chatService).getUnread(argThat(u -> "ana".equals(u.getUsername())));
    }

    @Test
    void givenNothingUnread_whenGetUnread_thenAnEmptyList() throws Exception {
        when(chatService.getUnread(argThat(u -> true))).thenReturn(Map.of());

        mockMvc.perform(get(UNREAD).with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
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
