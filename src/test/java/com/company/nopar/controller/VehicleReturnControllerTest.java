package com.company.nopar.controller;

import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.VehicleReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleReturnController.class)
@AutoConfigureMockMvc(addFilters = false)
class VehicleReturnControllerTest {

    private static final String RETURN_URL = "/api/v1/fleet/vehicles/5/return";
    private static final String VALID_BODY = "{\"kilometers\": 15200}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleReturnService vehicleReturnService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenReturnVehicle_thenCreatedWithThePublicView() throws Exception {
        when(vehicleReturnService.returnVehicle(eq(5L), any(), any())).thenReturn(savedReturn());

        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.vehicleId").value(5))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1234-ABC"))
                .andExpect(jsonPath("$.returnedByUsername").value("driver1"))
                .andExpect(jsonPath("$.kilometers").value(15200))
                .andExpect(jsonPath("$.vehicleStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.damagesReported").doesNotExist())
                .andExpect(jsonPath("$.returnedBy").doesNotExist());
    }

    @Test
    void givenKilometersAndDamages_whenReturnVehicle_thenTheServiceReceivesBoth() throws Exception {
        when(vehicleReturnService.returnVehicle(eq(5L), any(), any())).thenReturn(savedReturn());

        mockMvc.perform(post(RETURN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kilometers\": 15200, \"damagesReported\": \"Espejo roto\"}"))
                .andExpect(status().isCreated());

        verify(vehicleReturnService).returnVehicle(eq(5L), eq(new VehicleReturnRequest(15200, "Espejo roto")), any());
    }

    @Test
    void givenNoKilometers_whenReturnVehicle_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.kilometers").value("Los kilómetros son obligatorios"));

        verifyNoInteractions(vehicleReturnService);
    }

    @Test
    void givenNegativeKilometers_whenReturnVehicle_thenBadRequest() throws Exception {
        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content("{\"kilometers\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kilometers").value("Los kilómetros no pueden ser negativos"));

        verifyNoInteractions(vehicleReturnService);
    }

    @Test
    void givenTooLongDamagesDescription_whenReturnVehicle_thenBadRequest() throws Exception {
        String body = "{\"kilometers\": 15200, \"damagesReported\": \"" + "a".repeat(2001) + "\"}";

        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.damagesReported").exists());

        verifyNoInteractions(vehicleReturnService);
    }

    @Test
    void givenUnknownVehicle_whenReturnVehicle_thenNotFound() throws Exception {
        when(vehicleReturnService.returnVehicle(eq(5L), any(), any()))
                .thenThrow(new ResourceNotFoundException("Vehículo no encontrado con ID: 5"));

        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    @Test
    void givenVehicleNotInUse_whenReturnVehicle_thenConflict() throws Exception {
        when(vehicleReturnService.returnVehicle(eq(5L), any(), any()))
                .thenThrow(new InvalidStateException("El vehículo 5 no está en uso (estado: AVAILABLE)"));

        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"));
    }

    @Test
    void givenKilometersBelowTheRegisteredOnes_whenReturnVehicle_thenConflict() throws Exception {
        when(vehicleReturnService.returnVehicle(eq(5L), any(), any()))
                .thenThrow(InvalidKilometersException.lowerThanRegistered(14999, 15000));

        mockMvc.perform(post(RETURN_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Kilómetros no válidos"));
    }

    private static VehicleReturn savedReturn() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");
        vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);

        User driver = new User();
        driver.setId(7L);
        driver.setUsername("driver1");

        VehicleReturn vehicleReturn = new VehicleReturn();
        vehicleReturn.setId(3L);
        vehicleReturn.setVehicle(vehicle);
        vehicleReturn.setReturnedBy(driver);
        vehicleReturn.setKilometers(15200);
        return vehicleReturn;
    }
}
