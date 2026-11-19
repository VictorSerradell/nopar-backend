package com.company.nopar.controller;

import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftUpdateRequest;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.ShiftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminShiftController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminShiftControllerTest {

    private static final String URL = "/api/v1/admin/shifts";

    private static final String VALID_BODY = """
            {"workDate": "2026-11-19", "waveId": 1, "driverId": 2, "vehicleId": 3}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShiftService shiftService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenCreateShift_thenCreatedWithThePublicView() throws Exception {
        when(shiftService.createShift(any(), any())).thenReturn(shift(4L));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.workDate").value("2026-11-19"))
                .andExpect(jsonPath("$.waveName").value("Ola 1"))
                .andExpect(jsonPath("$.waveStartTime", startsWith("09:30")))
                .andExpect(jsonPath("$.driverUsername").value("driver1"))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$.driver").doesNotExist())
                .andExpect(jsonPath("$..password").isEmpty());

        verify(shiftService).createShift(eq(new ShiftCreateRequest(LocalDate.of(2026, 11, 19), 1L, 2L, 3L)), any());
    }

    @Test
    void givenEmptyBody_whenCreateShift_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.workDate").value("La fecha es obligatoria"))
                .andExpect(jsonPath("$.errors.waveId").value("El ID de la ola es obligatorio"))
                .andExpect(jsonPath("$.errors.driverId").value("El ID del conductor es obligatorio"))
                .andExpect(jsonPath("$.errors.vehicleId").value("El ID del vehículo es obligatorio"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenMalformedDate_whenCreateShift_thenBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"workDate": "19/11/2026", "waveId": 1, "driverId": 2, "vehicleId": 3}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenUnknownWaveDriverOrVehicle_whenCreateShift_thenNotFound() throws Exception {
        when(shiftService.createShift(any(), any()))
                .thenThrow(new ResourceNotFoundException("Ola no encontrada con ID: 1"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    @Test
    void givenAUserWhoIsNotADriver_whenCreateShift_thenBadRequestWithTheReason() throws Exception {
        when(shiftService.createShift(any(), any()))
                .thenThrow(new InvalidRequestException("El usuario admin1 no es un conductor"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.detail").value("El usuario admin1 no es un conductor"));
    }

    @Test
    void givenDriverOrVehicleAlreadyAssigned_whenCreateShift_thenConflict() throws Exception {
        when(shiftService.createShift(any(), any()))
                .thenThrow(new DuplicateResourceException("El conductor o la furgoneta ya tienen un turno ese día"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void givenSomeFields_whenUpdateShift_thenTheServiceReceivesOnlyThose() throws Exception {
        when(shiftService.updateShift(eq(4L), any())).thenReturn(shift(4L));

        mockMvc.perform(patch(URL + "/4").contentType(MediaType.APPLICATION_JSON).content("""
                        {"vehicleId": 9}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4));

        verify(shiftService).updateShift(eq(4L), eq(new ShiftUpdateRequest(null, null, null, 9L)));
    }

    @Test
    void givenNoChanges_whenUpdateShift_thenBadRequest() throws Exception {
        when(shiftService.updateShift(eq(4L), any()))
                .thenThrow(new InvalidRequestException("No se ha indicado ningún cambio"));

        mockMvc.perform(patch(URL + "/4").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("No se ha indicado ningún cambio"));
    }

    @Test
    void givenBusyDriverOrVehicle_whenUpdateShift_thenConflict() throws Exception {
        when(shiftService.updateShift(eq(4L), any()))
                .thenThrow(new DuplicateResourceException("El conductor o la furgoneta ya tienen un turno ese día"));

        mockMvc.perform(patch(URL + "/4").contentType(MediaType.APPLICATION_JSON).content("""
                        {"workDate": "2026-11-20"}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void givenUnknownShift_whenUpdateShift_thenNotFound() throws Exception {
        when(shiftService.updateShift(eq(9L), any()))
                .thenThrow(new ResourceNotFoundException("Turno no encontrado con ID: 9"));

        mockMvc.perform(patch(URL + "/9").contentType(MediaType.APPLICATION_JSON).content("""
                        {"vehicleId": 9}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenExistingShift_whenDeleteShift_thenNoContent() throws Exception {
        mockMvc.perform(delete(URL + "/4"))
                .andExpect(status().isNoContent());

        verify(shiftService).deleteShift(4L);
    }

    @Test
    void givenUnknownShift_whenDeleteShift_thenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Turno no encontrado con ID: 9")).when(shiftService).deleteShift(9L);

        mockMvc.perform(delete(URL + "/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    private static ShiftAssignment shift(Long id) {
        Wave wave = new Wave();
        wave.setId(1L);
        wave.setName("Ola 1");
        wave.setStartTime(LocalTime.of(9, 30));
        User driver = new User();
        driver.setId(2L);
        driver.setUsername("driver1");
        driver.setPassword("not-a-real-hash");
        driver.setRole(User.Role.DRIVER);
        Vehicle van = new Vehicle();
        van.setId(3L);
        van.setLicensePlate("1111AAA");

        ShiftAssignment shift = new ShiftAssignment();
        shift.setId(id);
        shift.setWorkDate(LocalDate.of(2026, 11, 19));
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(van);
        return shift;
    }
}
