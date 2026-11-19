package com.company.nopar.controller;

import com.company.nopar.dto.AnnouncementRequest;
import com.company.nopar.dto.AnnouncementUpdateRequest;
import com.company.nopar.entity.Announcement;
import com.company.nopar.entity.AnnouncementRead;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAnnouncementController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminAnnouncementControllerTest {

    private static final String URL = "/api/v1/admin/announcements";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnnouncementService announcementService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenCreate_thenCreatedWithThePublicView() throws Exception {
        when(announcementService.createAnnouncement(any(), any())).thenReturn(announcement(5L));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "Lockers", "body": "Recordad cerrar la puerta", "pinned": true}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.title").value("Lockers"))
                .andExpect(jsonPath("$.body").value("Recordad cerrar la puerta"))
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.createdByUsername").value("admin1"))
                .andExpect(jsonPath("$.createdAt", startsWith("2026-11-19T09:00")))
                .andExpect(jsonPath("$.createdBy").doesNotExist())
                .andExpect(jsonPath("$..password").isEmpty());

        verify(announcementService).createAnnouncement(
                eq(new AnnouncementRequest("Lockers", "Recordad cerrar la puerta", true)), any());
    }

    @Test
    void givenEmptyBody_whenCreate_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.title").value("El título es obligatorio"))
                .andExpect(jsonPath("$.errors.body").value("El texto es obligatorio"));

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenBlankTitle_whenCreate_thenBadRequest() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "   ", "body": "Texto"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").value("El título es obligatorio"));

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenTooLongTitleAndBody_whenCreate_thenBadRequest() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "%s", "body": "%s"}
                        """.formatted("t".repeat(121), "b".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").value("El título no puede superar los 120 caracteres"))
                .andExpect(jsonPath("$.errors.body").value("El texto no puede superar los 4000 caracteres"));

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenLimitLengths_whenCreate_thenAccepted() throws Exception {
        when(announcementService.createAnnouncement(any(), any())).thenReturn(announcement(5L));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "%s", "body": "%s"}
                        """.formatted("t".repeat(120), "b".repeat(4000))))
                .andExpect(status().isCreated());
    }

    @Test
    void givenSomeFields_whenUpdate_thenTheServiceReceivesOnlyThose() throws Exception {
        when(announcementService.updateAnnouncement(eq(5L), any())).thenReturn(announcement(5L));

        mockMvc.perform(patch(URL + "/5").contentType(MediaType.APPLICATION_JSON).content("""
                        {"pinned": false}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));

        verify(announcementService).updateAnnouncement(eq(5L), eq(new AnnouncementUpdateRequest(null, null, false)));
    }

    @Test
    void givenEmptyTitle_whenUpdate_thenBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(patch(URL + "/5").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": ""}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").value("El título debe tener entre 1 y 120 caracteres"));

        verifyNoInteractions(announcementService);
    }

    @Test
    void givenNoChanges_whenUpdate_thenBadRequestWithTheReason() throws Exception {
        when(announcementService.updateAnnouncement(eq(5L), any()))
                .thenThrow(new InvalidRequestException("No se ha indicado ningún cambio"));

        mockMvc.perform(patch(URL + "/5").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No se ha indicado ningún cambio"));
    }

    @Test
    void givenUnknownAnnouncement_whenUpdate_thenNotFound() throws Exception {
        when(announcementService.updateAnnouncement(eq(9L), any()))
                .thenThrow(new ResourceNotFoundException("Aviso no encontrado con ID: 9"));

        mockMvc.perform(patch(URL + "/9").contentType(MediaType.APPLICATION_JSON).content("""
                        {"pinned": true}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    @Test
    void givenExistingAnnouncement_whenDelete_thenNoContent() throws Exception {
        mockMvc.perform(delete(URL + "/5"))
                .andExpect(status().isNoContent());

        verify(announcementService).deleteAnnouncement(5L);
    }

    @Test
    void givenUnknownAnnouncement_whenDelete_thenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Aviso no encontrado con ID: 9")).when(announcementService).deleteAnnouncement(9L);

        mockMvc.perform(delete(URL + "/9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void whenGetReaders_thenListsWhoReadItAndWhen() throws Exception {
        when(announcementService.getReaders(5L)).thenReturn(List.of(read(7L, "driver1"), read(8L, "driver2")));

        mockMvc.perform(get(URL + "/5/reads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[0].username").value("driver1"))
                .andExpect(jsonPath("$[0].readAt", startsWith("2026-11-19T10:00")))
                .andExpect(jsonPath("$[1].username").value("driver2"))
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenUnknownAnnouncement_whenGetReaders_thenNotFound() throws Exception {
        when(announcementService.getReaders(9L)).thenThrow(new ResourceNotFoundException("Aviso no encontrado con ID: 9"));

        mockMvc.perform(get(URL + "/9/reads"))
                .andExpect(status().isNotFound());
    }

    private static Announcement announcement(Long id) {
        User author = new User();
        author.setId(1L);
        author.setUsername("admin1");
        author.setPassword("not-a-real-hash");

        Announcement announcement = new Announcement();
        announcement.setId(id);
        announcement.setTitle("Lockers");
        announcement.setBody("Recordad cerrar la puerta");
        announcement.setPinned(true);
        announcement.setCreatedBy(author);
        announcement.setCreatedAt(LocalDateTime.of(2026, 11, 19, 9, 0));
        announcement.setUpdatedAt(LocalDateTime.of(2026, 11, 19, 9, 0));
        return announcement;
    }

    private static AnnouncementRead read(Long userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");

        AnnouncementRead read = new AnnouncementRead();
        read.setUser(user);
        read.setReadAt(LocalDateTime.of(2026, 11, 19, 10, 0));
        return read;
    }
}
