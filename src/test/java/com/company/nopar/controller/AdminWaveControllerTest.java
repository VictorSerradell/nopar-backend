package com.company.nopar.controller;

import com.company.nopar.dto.WaveRequest;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.ResourceInUseException;
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

import java.time.LocalTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminWaveController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminWaveControllerTest {

    private static final String URL = "/api/v1/admin/waves";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShiftService shiftService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenCreateWave_thenCreatedWithTheWave() throws Exception {
        when(shiftService.createWave(any())).thenReturn(wave(1L, "Ola 1", LocalTime.of(9, 30)));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Ola 1", "startTime": "09:30"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ola 1"))
                .andExpect(jsonPath("$.startTime", startsWith("09:30")));

        verify(shiftService).createWave(eq(new WaveRequest("Ola 1", LocalTime.of(9, 30))));
    }

    @Test
    void givenEmptyBody_whenCreateWave_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.name").value("El nombre de la ola es obligatorio"))
                .andExpect(jsonPath("$.errors.startTime").value("La hora de salida es obligatoria"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenBlankName_whenCreateWave_thenBadRequest() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "   ", "startTime": "09:30"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("El nombre de la ola es obligatorio"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenTooLongName_whenCreateWave_thenBadRequest() throws Exception {
        String name = "x".repeat(51);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "%s", "startTime": "09:30"}
                        """.formatted(name)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("El nombre de la ola no puede superar los 50 caracteres"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenImpossibleTime_whenCreateWave_thenBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Ola 1", "startTime": "25:99"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"));

        verifyNoInteractions(shiftService);
    }

    @Test
    void givenExistingName_whenCreateWave_thenConflict() throws Exception {
        when(shiftService.createWave(any())).thenThrow(new DuplicateResourceException("Ya existe una ola con el nombre: Ola 1"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Ola 1", "startTime": "09:30"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void whenGetWaves_thenListsThemInTheServiceOrder() throws Exception {
        when(shiftService.getWaves()).thenReturn(List.of(
                wave(1L, "Ola 1", LocalTime.of(9, 30)), wave(2L, "Ola 2", LocalTime.of(11, 0))));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Ola 1"))
                .andExpect(jsonPath("$[1].name").value("Ola 2"))
                .andExpect(jsonPath("$[1].startTime", startsWith("11:00")));
    }

    @Test
    void givenExistingWave_whenDeleteWave_thenNoContent() throws Exception {
        mockMvc.perform(delete(URL + "/1"))
                .andExpect(status().isNoContent());

        verify(shiftService).deleteWave(1L);
    }

    @Test
    void givenUnknownWave_whenDeleteWave_thenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Ola no encontrada con ID: 9")).when(shiftService).deleteWave(9L);

        mockMvc.perform(delete(URL + "/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    @Test
    void givenWaveWithShifts_whenDeleteWave_thenConflict() throws Exception {
        doThrow(new ResourceInUseException("No se puede eliminar la ola 1 porque tiene turnos asignados"))
                .when(shiftService).deleteWave(1L);

        mockMvc.perform(delete(URL + "/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso en uso"));
    }

    private static Wave wave(Long id, String name, LocalTime startTime) {
        Wave wave = new Wave();
        wave.setId(id);
        wave.setName(name);
        wave.setStartTime(startTime);
        return wave;
    }
}
