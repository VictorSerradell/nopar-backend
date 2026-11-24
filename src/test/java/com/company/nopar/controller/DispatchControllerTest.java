package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.entity.Wave;
import com.company.nopar.entity.WaveCall;
import com.company.nopar.entity.WaveCallRecipient;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.ShiftService;
import com.company.nopar.service.WaveCallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con la cadena de seguridad real: /api/v1/dispatch es para administradores y encargados, y quien llama sale del
 * token (no lo envía el cliente).
 */
@WebMvcTest(DispatchController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DispatchControllerTest {

    private static final String CALL_URL = "/api/v1/dispatch/waves/1/calls";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaveCallService waveCallService;

    @MockitoBean
    private ShiftService shiftService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    // ---------------------------------------------------------------- quién puede

    @Test
    void givenNoToken_whenCallWave_thenUnauthorized() throws Exception {
        mockMvc.perform(post(CALL_URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenADriver_whenCallWave_thenForbidden() throws Exception {
        mockMvc.perform(post(CALL_URL).with(user(newUser(2L, "ana", User.Role.DRIVER))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenADriver_whenGetCalls_thenForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/dispatch/wave-calls").with(user(newUser(2L, "ana", User.Role.DRIVER))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(waveCallService);
    }

    // ---------------------------------------------------------------- llamar

    @Test
    void givenAManager_whenCallWaveWithoutBody_thenCreatedAndTheServiceGetsNoMessageAndTheManager() throws Exception {
        when(waveCallService.callWave(eq(1L), isNull(), any(User.class))).thenReturn(call());

        mockMvc.perform(post(CALL_URL).with(user(manager())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.waveName").value("Ola 1"))
                .andExpect(jsonPath("$.waveStartTime", startsWith("09:30")))
                .andExpect(jsonPath("$.workDate").value("2026-11-24"))
                .andExpect(jsonPath("$.message").value("Subid"))
                .andExpect(jsonPath("$.calledByUsername").value("encargado1"))
                .andExpect(jsonPath("$.recipientCount").value(2))
                .andExpect(jsonPath("$.acknowledgedCount").value(1))
                // ordenados por nombre: ana (aún no ha confirmado) y bea (ya confirmó)
                .andExpect(jsonPath("$.recipients[0].driverUsername").value("ana"))
                .andExpect(jsonPath("$.recipients[0].vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$.recipients[0].acknowledged").value(false))
                .andExpect(jsonPath("$.recipients[0].acknowledgedAt").doesNotExist())
                .andExpect(jsonPath("$.recipients[1].driverUsername").value("bea"))
                .andExpect(jsonPath("$.recipients[1].acknowledged").value(true))
                .andExpect(jsonPath("$.recipients[1].acknowledgedAt", startsWith("2026-11-24T09:26")))
                .andExpect(jsonPath("$..password").isEmpty());

        verify(waveCallService).callWave(eq(1L), isNull(), argThat(u -> "encargado1".equals(u.getUsername())));
    }

    @Test
    void givenAnAdmin_whenCallWaveWithAMessage_thenTheServiceGetsIt() throws Exception {
        when(waveCallService.callWave(eq(1L), eq("Bajad al muelle"), any(User.class))).thenReturn(call());

        mockMvc.perform(post(CALL_URL).with(user(newUser(1L, "admin1", User.Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Bajad al muelle\"}"))
                .andExpect(status().isCreated());

        verify(waveCallService).callWave(eq(1L), eq("Bajad al muelle"), argThat(u -> "admin1".equals(u.getUsername())));
    }

    @Test
    void givenAMessageOver140Characters_whenCallWave_thenBadRequestAndNobodyIsCalled() throws Exception {
        mockMvc.perform(post(CALL_URL).with(user(manager()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + "x".repeat(141) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.message").exists());

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenNobodyWorksTheWaveToday_whenCallWave_thenConflictWithTheReason() throws Exception {
        when(waveCallService.callWave(eq(1L), isNull(), any(User.class)))
                .thenThrow(new InvalidStateException("Hoy no hay ningún conductor con turno en la ola Ola 1"));

        mockMvc.perform(post(CALL_URL).with(user(manager())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"))
                .andExpect(jsonPath("$.detail").value("Hoy no hay ningún conductor con turno en la ola Ola 1"));
    }

    @Test
    void givenAnUnknownWave_whenCallWave_thenNotFound() throws Exception {
        when(waveCallService.callWave(eq(1L), isNull(), any(User.class)))
                .thenThrow(new ResourceNotFoundException("Ola no encontrada con ID: 1"));

        mockMvc.perform(post(CALL_URL).with(user(manager())))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- consultas

    @Test
    void givenAManager_whenGetWaves_thenTheyAreListedToKnowWhichToCall() throws Exception {
        Wave wave = wave();
        when(shiftService.getWaves()).thenReturn(List.of(wave));

        mockMvc.perform(get("/api/v1/dispatch/waves").with(user(manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Ola 1"));
    }

    @Test
    void givenADate_whenGetCalls_thenTheServiceGetsThatDay() throws Exception {
        when(waveCallService.getCalls(LocalDate.of(2026, 11, 24))).thenReturn(List.of(call()));

        mockMvc.perform(get("/api/v1/dispatch/wave-calls").param("date", "2026-11-24").with(user(manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].acknowledgedCount").value(1));
    }

    @Test
    void givenNoDate_whenGetCalls_thenItIsToday() throws Exception {
        when(waveCallService.getCalls(any(LocalDate.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dispatch/wave-calls").with(user(manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(waveCallService).getCalls(LocalDate.now());
    }

    @Test
    void givenAMalformedDate_whenGetCalls_thenBadRequestNamingTheParameter() throws Exception {
        mockMvc.perform(get("/api/v1/dispatch/wave-calls").param("date", "hoy").with(user(manager())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El parámetro 'date' no tiene un valor válido"));

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenACall_whenGetCall_thenWhoHasAndHasNotConfirmed() throws Exception {
        when(waveCallService.getCall(7L)).thenReturn(call());

        mockMvc.perform(get("/api/v1/dispatch/wave-calls/7").with(user(manager())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(2))
                .andExpect(jsonPath("$.acknowledgedCount").value(1));
    }

    @Test
    void givenAnUnknownCall_whenGetCall_thenNotFound() throws Exception {
        when(waveCallService.getCall(8L)).thenThrow(new ResourceNotFoundException("Llamada no encontrada con ID: 8"));

        mockMvc.perform(get("/api/v1/dispatch/wave-calls/8").with(user(manager())))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- datos de prueba

    private static User manager() {
        return newUser(5L, "encargado1", User.Role.MANAGER);
    }

    private static User newUser(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return user;
    }

    private static Wave wave() {
        Wave wave = new Wave();
        wave.setId(1L);
        wave.setName("Ola 1");
        wave.setStartTime(LocalTime.of(9, 30));
        return wave;
    }

    /** Ola 1, llamada por el encargado; ana aún no ha confirmado y bea sí (a las 09:26). */
    private static WaveCall call() {
        WaveCall call = new WaveCall();
        call.setId(7L);
        call.setWave(wave());
        call.setWorkDate(LocalDate.of(2026, 11, 24));
        call.setMessage("Subid");
        call.setCalledBy(manager());
        call.setCalledAt(LocalDateTime.of(2026, 11, 24, 9, 25));
        call.getRecipients().add(recipient(call, 3L, "bea", "2222BBB", LocalDateTime.of(2026, 11, 24, 9, 26)));
        call.getRecipients().add(recipient(call, 2L, "ana", "1111AAA", null));
        return call;
    }

    private static WaveCallRecipient recipient(WaveCall call, Long driverId, String username, String plate,
                                               LocalDateTime acknowledgedAt) {
        WaveCallRecipient recipient = new WaveCallRecipient();
        recipient.setWaveCall(call);
        recipient.setDriver(newUser(driverId, username, User.Role.DRIVER));
        recipient.setVehicleLicensePlate(plate);
        recipient.setAcknowledgedAt(acknowledgedAt);
        return recipient;
    }
}
