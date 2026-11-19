package com.company.nopar.controller;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.ShiftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con la cadena de seguridad real: "mis turnos" usa al conductor del token y las reglas de rol de @PreAuthorize
 * se aplican de verdad (en las pruebas sin filtros no hay usuario autenticado).
 */
@WebMvcTest(ShiftController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ShiftControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 19);
    private static final LocalDate TO = LocalDate.of(2026, 11, 21);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShiftService shiftService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    void givenNoToken_whenGetShifts_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/shifts").param("from", "2026-11-19").param("to", "2026-11-21"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenADriver_whenGetShifts_thenSeesTheWholePlanWithoutInternalEntities() throws Exception {
        when(shiftService.getShifts(FROM, TO)).thenReturn(List.of(shift(4L, 2L, "driver1"), shift(5L, 6L, "driver2")));

        mockMvc.perform(get("/api/v1/shifts").param("from", "2026-11-19").param("to", "2026-11-21")
                        .with(user(driver(2L, "driver1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workDate").value("2026-11-19"))
                .andExpect(jsonPath("$[0].waveName").value("Ola 1"))
                .andExpect(jsonPath("$[0].waveStartTime", startsWith("09:30")))
                .andExpect(jsonPath("$[0].driverUsername").value("driver1"))
                .andExpect(jsonPath("$[0].vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$[1].driverUsername").value("driver2"))
                .andExpect(jsonPath("$[0].driver").doesNotExist())
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenAnAdmin_whenGetShifts_thenOk() throws Exception {
        when(shiftService.getShifts(FROM, TO)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/shifts").param("from", "2026-11-19").param("to", "2026-11-21")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void givenNoEndDate_whenGetShifts_thenBadRequestNamingTheParameter() throws Exception {
        mockMvc.perform(get("/api/v1/shifts").param("from", "2026-11-19").with(user(driver(2L, "driver1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.detail").value("Falta el parámetro 'to'"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenAMalformedDate_whenGetShifts_thenBadRequestNamingTheParameter() throws Exception {
        mockMvc.perform(get("/api/v1/shifts").param("from", "hoy").param("to", "2026-11-21")
                        .with(user(driver(2L, "driver1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.detail").value("El parámetro 'from' no tiene un valor válido"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenARangeTheServiceRejects_whenGetShifts_thenBadRequestWithTheReason() throws Exception {
        when(shiftService.getShifts(TO, FROM))
                .thenThrow(new InvalidRequestException("La fecha final no puede ser anterior a la inicial"));

        mockMvc.perform(get("/api/v1/shifts").param("from", "2026-11-21").param("to", "2026-11-19")
                        .with(user(driver(2L, "driver1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La fecha final no puede ser anterior a la inicial"));
    }

    @Test
    void givenNoToken_whenGetMyShifts_thenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/shifts/me").param("from", "2026-11-19").param("to", "2026-11-21"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenADriver_whenGetMyShifts_thenTheServiceReceivesTheDriverOfTheToken() throws Exception {
        when(shiftService.getShiftsOfDriver(argThat(u -> u != null && Long.valueOf(2L).equals(u.getId())), eq(FROM), eq(TO)))
                .thenReturn(List.of(shift(4L, 2L, "driver1")));

        mockMvc.perform(get("/api/v1/shifts/me").param("from", "2026-11-19").param("to", "2026-11-21")
                        .with(user(driver(2L, "driver1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].driverUsername").value("driver1"));

        verify(shiftService).getShiftsOfDriver(argThat(u -> u != null && "driver1".equals(u.getUsername())), eq(FROM), eq(TO));
    }

    @Test
    void givenAnAdmin_whenGetMyShifts_thenForbiddenBecauseAdminsDoNotDrive() throws Exception {
        mockMvc.perform(get("/api/v1/shifts/me").param("from", "2026-11-19").param("to", "2026-11-21")
                        .with(user(admin())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(shiftService);
    }

    private static User driver(Long id, String username) {
        return newUser(id, username, User.Role.DRIVER);
    }

    private static User admin() {
        return newUser(1L, "admin1", User.Role.ADMIN);
    }

    private static User newUser(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return user;
    }

    private static ShiftAssignment shift(Long id, Long driverId, String driverUsername) {
        Wave wave = new Wave();
        wave.setId(1L);
        wave.setName("Ola 1");
        wave.setStartTime(LocalTime.of(9, 30));
        Vehicle van = new Vehicle();
        van.setId(3L);
        van.setLicensePlate("1111AAA");

        ShiftAssignment shift = new ShiftAssignment();
        shift.setId(id);
        shift.setWorkDate(LocalDate.of(2026, 11, 19));
        shift.setWave(wave);
        shift.setDriver(driver(driverId, driverUsername));
        shift.setVehicle(van);
        return shift;
    }
}
