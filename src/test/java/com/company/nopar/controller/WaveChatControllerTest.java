package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.entity.WaveMessage;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.FileStorageService;
import com.company.nopar.service.WaveChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El chat de una ola por HTTP: quién entra, qué se envía al servicio y qué se contesta. Con la cadena de seguridad real. */
@WebMvcTest(WaveChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class WaveChatControllerTest {

    private static final String MESSAGES = "/api/v1/waves/1/chat/messages";
    private static final String PHOTOS = "/api/v1/waves/1/chat/photos";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaveChatService chatService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    // ---------------------------------------------------------------- leer

    @Test
    void givenNoToken_whenGetMessages_thenUnauthorized() throws Exception {
        mockMvc.perform(get(MESSAGES)).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenADriver_whenGetMessages_thenTheServiceGetsTheDriverOfTheTokenAndTheDefaultLimit() throws Exception {
        when(chatService.getMessages(eq(1L), isNull(), eq(100), any(User.class)))
                .thenReturn(List.of(message(10L, "hola", false), message(11L, null, true)));

        mockMvc.perform(get(MESSAGES).with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].waveId").value(1))
                .andExpect(jsonPath("$[0].senderUsername").value("encargado1"))
                .andExpect(jsonPath("$[0].senderRole").value("MANAGER"))
                .andExpect(jsonPath("$[0].text").value("hola"))
                .andExpect(jsonPath("$[0].photoUrl").doesNotExist())
                .andExpect(jsonPath("$[0].sentAt", startsWith("2026-11-24T09:26")))
                // una foto sin texto: la app descarga la imagen de esta ruta con su token
                .andExpect(jsonPath("$[1].text").doesNotExist())
                .andExpect(jsonPath("$[1].photoUrl").value("/api/v1/waves/1/chat/messages/11/photo"))
                .andExpect(jsonPath("$..password").isEmpty());

        verify(chatService).getMessages(eq(1L), isNull(), eq(100), argThat(u -> "ana".equals(u.getUsername())));
    }

    @Test
    void givenAfterAndLimit_whenGetMessages_thenTheServiceGetsThem() throws Exception {
        when(chatService.getMessages(eq(1L), eq(11L), eq(20), any(User.class))).thenReturn(List.of());

        mockMvc.perform(get(MESSAGES).param("after", "11").param("limit", "20").with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void givenManagersAndAdmins_whenGetMessages_thenTheyAlsoEnter() throws Exception {
        when(chatService.getMessages(eq(1L), isNull(), eq(100), any(User.class))).thenReturn(List.of());

        mockMvc.perform(get(MESSAGES).with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isOk());
        mockMvc.perform(get(MESSAGES).with(user(newUser(1L, "admin1", User.Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void givenParametersOutOfRange_whenGetMessages_thenBadRequestWithoutTouchingTheService() throws Exception {
        mockMvc.perform(get(MESSAGES).param("limit", "0").with(user(driver()))).andExpect(status().isBadRequest());
        mockMvc.perform(get(MESSAGES).param("limit", "201").with(user(driver()))).andExpect(status().isBadRequest());
        mockMvc.perform(get(MESSAGES).param("after", "-1").with(user(driver()))).andExpect(status().isBadRequest());
        mockMvc.perform(get(MESSAGES).param("after", "abc").with(user(driver()))).andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenADriverWithoutAShiftInTheWave_whenGetMessages_thenForbidden() throws Exception {
        when(chatService.getMessages(eq(1L), isNull(), eq(100), any(User.class)))
                .thenThrow(new AccessDeniedException("Hoy no tienes turno en esta ola"));

        mockMvc.perform(get(MESSAGES).with(user(driver()))).andExpect(status().isForbidden());
    }

    @Test
    void givenAnUnknownWave_whenGetMessages_thenNotFound() throws Exception {
        when(chatService.getMessages(eq(9L), isNull(), eq(100), any(User.class)))
                .thenThrow(new ResourceNotFoundException("Ola no encontrada con ID: 9"));

        mockMvc.perform(get("/api/v1/waves/9/chat/messages").with(user(driver())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Ola no encontrada con ID: 9"));
    }

    // ---------------------------------------------------------------- escribir texto

    @Test
    void givenNoToken_whenSendText_thenUnauthorized() throws Exception {
        mockMvc.perform(post(MESSAGES).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hola\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenAText_whenSendText_thenCreatedAndTheServiceGetsTheSenderOfTheToken() throws Exception {
        when(chatService.send(eq(1L), eq("hola"), isNull(), any(User.class))).thenReturn(message(12L, "hola", false));

        mockMvc.perform(post(MESSAGES).with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hola\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.text").value("hola"));

        verify(chatService).send(eq(1L), eq("hola"), isNull(), argThat(u -> Long.valueOf(2L).equals(u.getId())));
    }

    @Test
    void givenAnEmptyOrTooLongMessage_whenSendText_thenBadRequestWithTheServiceReason() throws Exception {
        when(chatService.send(eq(1L), eq(" "), isNull(), any(User.class)))
                .thenThrow(new InvalidRequestException("Escribe un mensaje o adjunta una foto"));

        mockMvc.perform(post(MESSAGES).with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Escribe un mensaje o adjunta una foto"));
    }

    @Test
    void givenABodyThatIsNotJson_whenSendText_thenBadRequestWithoutTouchingTheService() throws Exception {
        mockMvc.perform(post(MESSAGES).with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON).content("{texto"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(MESSAGES).with(user(driver())).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenADriverWithoutAShiftInTheWave_whenSendText_thenForbidden() throws Exception {
        when(chatService.send(eq(1L), eq("hola"), isNull(), any(User.class)))
                .thenThrow(new AccessDeniedException("Hoy no tienes turno en esta ola"));

        mockMvc.perform(post(MESSAGES).with(user(driver()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hola\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- escribir con foto

    @Test
    void givenAPhotoWithText_whenSendPhoto_thenCreatedAndTheServiceGetsBoth() throws Exception {
        when(chatService.send(eq(1L), eq("Mirad"), any(), any(User.class))).thenReturn(message(13L, "Mirad", true));

        mockMvc.perform(multipart(PHOTOS).file(photo()).file(data("{\"text\":\"Mirad\"}")).with(user(driver())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(13))
                .andExpect(jsonPath("$.text").value("Mirad"))
                .andExpect(jsonPath("$.photoUrl").value("/api/v1/waves/1/chat/messages/13/photo"));

        verify(chatService).send(eq(1L), eq("Mirad"), argThat(file -> file != null && !file.isEmpty()),
                argThat(u -> "ana".equals(u.getUsername())));
    }

    @Test
    void givenOnlyAPhoto_whenSendPhoto_thenTheTextIsNull() throws Exception {
        when(chatService.send(eq(1L), isNull(), any(), any(User.class))).thenReturn(message(14L, null, true));

        mockMvc.perform(multipart(PHOTOS).file(photo()).with(user(driver())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").doesNotExist());
    }

    @Test
    void givenNoPhotoPart_whenSendMultipart_thenBadRequestWithoutTouchingTheService() throws Exception {
        mockMvc.perform(multipart(PHOTOS).file(data("{\"text\":\"hola\"}")).with(user(driver())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenAFileThatIsNotAnImage_whenSendPhoto_thenBadRequestWithProblemDetail() throws Exception {
        when(chatService.send(eq(1L), isNull(), any(), any(User.class)))
                .thenThrow(new InvalidFileException("Formato de archivo no permitido. Solo se aceptan imágenes JPEG y PNG."));

        mockMvc.perform(multipart(PHOTOS).file(photo()).with(user(driver())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Archivo no válido"));
    }

    @Test
    void givenNoToken_whenSendPhoto_thenUnauthorized() throws Exception {
        mockMvc.perform(multipart(PHOTOS).file(photo())).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    // ---------------------------------------------------------------- fotos

    @Test
    void givenAStoredPhoto_whenGetPhoto_thenImageBytesWithItsTypeAndPrivateCache() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        when(chatService.getPhoto(eq(1L), eq(11L), any(User.class)))
                .thenReturn(new FileStorageService.StoredFile(new ByteArrayResource(jpeg), MediaType.IMAGE_JPEG));

        mockMvc.perform(get(MESSAGES + "/11/photo").with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg))
                .andExpect(header().string("Cache-Control", "max-age=3600, private"));
    }

    @Test
    void givenNoToken_whenGetPhoto_thenUnauthorized() throws Exception {
        mockMvc.perform(get(MESSAGES + "/11/photo")).andExpect(status().isUnauthorized());

        verifyNoInteractions(chatService);
    }

    @Test
    void givenAMessageWithoutPhoto_whenGetPhoto_thenNotFound() throws Exception {
        when(chatService.getPhoto(eq(1L), eq(10L), any(User.class)))
                .thenThrow(new ResourceNotFoundException("El mensaje no lleva foto"));

        mockMvc.perform(get(MESSAGES + "/10/photo").with(user(driver())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El mensaje no lleva foto"));
    }

    @Test
    void givenADriverWithoutAShiftInTheWave_whenGetPhoto_thenForbidden() throws Exception {
        when(chatService.getPhoto(eq(1L), eq(11L), any(User.class)))
                .thenThrow(new AccessDeniedException("Hoy no tienes turno en esta ola"));

        mockMvc.perform(get(MESSAGES + "/11/photo").with(user(driver()))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- datos de prueba

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photo", "x.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    private static MockMultipartFile data(String json) {
        return new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
    }

    private static User driver() {
        return newUser(2L, "ana", User.Role.DRIVER);
    }

    private static User newUser(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return user;
    }

    private static WaveMessage message(Long id, String body, boolean withPhoto) {
        WaveMessage message = new WaveMessage();
        message.setId(id);
        message.setWaveId(1L);
        message.setWorkDate(LocalDate.of(2026, 11, 24));
        message.setSender(newUser(5L, "encargado1", User.Role.MANAGER));
        message.setBody(body);
        message.setPhotoFile(withPhoto ? "abc.jpg" : null);
        message.setSentAt(LocalDateTime.of(2026, 11, 24, 9, 26, 30));
        return message;
    }
}
