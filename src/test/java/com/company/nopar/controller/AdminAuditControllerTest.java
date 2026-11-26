package com.company.nopar.controller;

import com.company.nopar.entity.AuditEvent;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAuditController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenEvents_whenList_thenReturnsThemInAPage() throws Exception {
        AuditEvent event = new AuditEvent();
        event.setId(5L);
        event.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 15));
        event.setAction(AuditEvent.Action.USER_DEACTIVATED);
        event.setActorUsername("jefe");
        event.setTargetUserId(7L);
        event.setTargetUsername("conductor1");
        event.setDetail("rol DRIVER");
        when(auditService.list(0, 20)).thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].action").value("USER_DEACTIVATED"))
                .andExpect(jsonPath("$.content[0].actorUsername").value("jefe"))
                .andExpect(jsonPath("$.content[0].targetUserId").value(7))
                .andExpect(jsonPath("$.content[0].targetUsername").value("conductor1"))
                .andExpect(jsonPath("$.content[0].detail").value("rol DRIVER"))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void givenAPageAndSize_whenList_thenTheyAreUsed() throws Exception {
        when(auditService.list(2, 50)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        mockMvc.perform(get("/api/v1/admin/audit").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void givenAnInvalidPageOrSize_whenList_thenBadRequestAndNothingIsRead() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/audit").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/audit").param("size", "101")).andExpect(status().isBadRequest());

        verifyNoInteractions(auditService);
    }
}
