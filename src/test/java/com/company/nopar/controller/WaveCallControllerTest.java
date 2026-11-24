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
import com.company.nopar.service.WaveCallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lo que ve y hace el conductor: sus llamadas de hoy y el botón "Enterado". Con la cadena de seguridad real. */
@WebMvcTest(WaveCallController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class WaveCallControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaveCallService waveCallService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    // ---------------------------------------------------------------- mis llamadas

    @Test
    void givenNoToken_whenGetMyCalls_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/wave-calls/me")).andExpect(status().isUnauthorized());

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenADriver_whenGetMyCalls_thenTheServiceGetsTheDriverOfTheTokenAndToday() throws Exception {
        when(waveCallService.getCallsOfDriver(any(User.class), any(LocalDate.class)))
                .thenReturn(List.of(recipient(null, LocalDateTime.now().plusMinutes(30))));

        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].waveName").value("Ola 1"))
                .andExpect(jsonPath("$[0].waveStartTime", startsWith("09:30")))
                .andExpect(jsonPath("$[0].message").value("Subid"))
                .andExpect(jsonPath("$[0].calledByUsername").value("encargado1"))
                .andExpect(jsonPath("$[0].vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[0].acknowledgedAt").doesNotExist())
                .andExpect(jsonPath("$[0].expiresAt").exists())
                // aún vigente y sin confirmar: la app enseña el botón «Enterado»
                .andExpect(jsonPath("$[0].canAcknowledge").value(true))
                .andExpect(jsonPath("$..password").isEmpty());

        verify(waveCallService).getCallsOfDriver(argThat(u -> "ana".equals(u.getUsername())), eq(LocalDate.now()));
    }

    @Test
    void givenAnExpiredOrAnAcknowledgedCall_whenGetMyCalls_thenTheAppIsNotToldToOfferEnterado() throws Exception {
        when(waveCallService.getCallsOfDriver(any(User.class), any(LocalDate.class))).thenReturn(List.of(
                recipient(null, LocalDateTime.now().minusMinutes(1)),
                recipient(LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(30))));

        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[0].canAcknowledge").value(false))
                .andExpect(jsonPath("$[1].acknowledged").value(true))
                .andExpect(jsonPath("$[1].canAcknowledge").value(false));
    }

    @Test
    void givenADate_whenGetMyCalls_thenTheServiceGetsThatDay() throws Exception {
        when(waveCallService.getCallsOfDriver(any(User.class), eq(LocalDate.of(2026, 11, 24)))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/wave-calls/me").param("date", "2026-11-24").with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void givenAManagerOrAnAdmin_whenGetMyCalls_thenForbiddenBecauseOnlyDriversReceiveCalls() throws Exception {
        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(newUser(1L, "admin1", User.Role.ADMIN))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(waveCallService);
    }

    // ---------------------------------------------------------------- Enterado

    @Test
    void givenNoToken_whenAcknowledge_thenUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/wave-calls/7/acknowledge")).andExpect(status().isUnauthorized());

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenADriver_whenAcknowledge_thenTheServiceGetsTheDriverOfTheTokenAndTheAnswerIsConfirmed() throws Exception {
        when(waveCallService.acknowledge(eq(7L), any(User.class)))
                .thenReturn(recipient(LocalDateTime.of(2026, 11, 24, 9, 26), LocalDateTime.of(2026, 11, 24, 10, 25)));

        mockMvc.perform(post("/api/v1/wave-calls/7/acknowledge").with(user(driver())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.acknowledged").value(true))
                .andExpect(jsonPath("$.acknowledgedAt", startsWith("2026-11-24T09:26")));

        verify(waveCallService).acknowledge(eq(7L), argThat(u -> Long.valueOf(2L).equals(u.getId())));
    }

    @Test
    void givenAManager_whenAcknowledge_thenForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/wave-calls/7/acknowledge").with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(waveCallService);
    }

    @Test
    void givenADriverWhoWasNotCalled_whenAcknowledge_thenForbidden() throws Exception {
        when(waveCallService.acknowledge(eq(7L), any(User.class)))
                .thenThrow(new AccessDeniedException("Esta llamada no es para ti"));

        mockMvc.perform(post("/api/v1/wave-calls/7/acknowledge").with(user(driver())))
                .andExpect(status().isForbidden());
    }

    @Test
    void givenTheOrderHasExpired_whenAcknowledge_thenConflict() throws Exception {
        when(waveCallService.acknowledge(eq(7L), any(User.class)))
                .thenThrow(new InvalidStateException("La orden de Ola 1 ya caducó: ya no se puede confirmar"));

        mockMvc.perform(post("/api/v1/wave-calls/7/acknowledge").with(user(driver())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La orden de Ola 1 ya caducó: ya no se puede confirmar"));
    }

    @Test
    void givenAnUnknownCall_whenAcknowledge_thenNotFound() throws Exception {
        when(waveCallService.acknowledge(eq(8L), any(User.class)))
                .thenThrow(new ResourceNotFoundException("Llamada no encontrada con ID: 8"));

        mockMvc.perform(post("/api/v1/wave-calls/8/acknowledge").with(user(driver())))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- datos de prueba

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

    private static WaveCallRecipient recipient(LocalDateTime acknowledgedAt, LocalDateTime expiresAt) {
        Wave wave = new Wave();
        wave.setId(1L);
        wave.setName("Ola 1");
        wave.setStartTime(LocalTime.of(9, 30));

        WaveCall call = new WaveCall();
        call.setId(7L);
        call.setWave(wave);
        call.setWorkDate(LocalDate.of(2026, 11, 24));
        call.setMessage("Subid");
        call.setCalledBy(newUser(5L, "encargado1", User.Role.MANAGER));
        call.setCalledAt(LocalDateTime.of(2026, 11, 24, 9, 25));
        call.setExpiresAt(expiresAt);

        WaveCallRecipient recipient = new WaveCallRecipient();
        recipient.setWaveCall(call);
        recipient.setDriver(driver());
        recipient.setVehicleLicensePlate("1111AAA");
        recipient.setAcknowledgedAt(acknowledgedAt);
        return recipient;
    }
}
