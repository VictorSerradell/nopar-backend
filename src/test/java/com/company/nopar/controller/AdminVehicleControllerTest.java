package com.company.nopar.controller;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminVehicleController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminVehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleService vehicleService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidStatus_whenChangeStatus_thenOkWithTheUpdatedVehicle() throws Exception {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setLicensePlate("1234-ABC");
        vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);
        when(vehicleService.changeVehicleStatus(10L, Vehicle.VehicleStatus.AVAILABLE)).thenReturn(vehicle);

        mockMvc.perform(patch("/api/v1/admin/vehicles/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "AVAILABLE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.licensePlate").value("1234-ABC"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void givenInUse_whenChangeStatus_thenBadRequestBecauseItIsSetByInspections() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/vehicles/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "IN_USE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"));

        verifyNoInteractions(vehicleService);
    }

    @Test
    void givenUnknownStatus_whenChangeStatus_thenBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/vehicles/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "BROKEN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"));

        verifyNoInteractions(vehicleService);
    }

    @Test
    void givenMissingStatus_whenChangeStatus_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/vehicles/10/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.status").value("El estado es obligatorio"));

        verifyNoInteractions(vehicleService);
    }

    @Test
    void givenUnknownVehicle_whenChangeStatus_thenNotFound() throws Exception {
        when(vehicleService.changeVehicleStatus(99L, Vehicle.VehicleStatus.MAINTENANCE))
                .thenThrow(new ResourceNotFoundException("Vehículo no encontrado con ID: 99"));

        mockMvc.perform(patch("/api/v1/admin/vehicles/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "MAINTENANCE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }
}
