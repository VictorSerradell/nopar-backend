package com.company.nopar.controller;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.VehicleInspectionService;
import com.company.nopar.service.VehicleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FleetController.class)
@AutoConfigureMockMvc(addFilters = false)
class FleetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Inicializamos el ObjectMapper de manera explícita para evitar problemas de resolución en el contexto de test
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private VehicleService vehicleService;

    @MockitoBean
    private VehicleInspectionService inspectionService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void testGetAllVehicles() throws Exception {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(1L);
        vehicle.setLicensePlate("1234-ABC");
        vehicle.setCurrentKilometers(15000);

        when(vehicleService.getAllVehicles()).thenReturn(List.of(vehicle));

        mockMvc.perform(get("/api/v1/fleet/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].licensePlate").value("1234-ABC"));
    }

    @Test
    void testRegisterVehicleSuccess() throws Exception {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate("5678-XYZ");
        vehicle.setCurrentKilometers(1000);

        when(vehicleService.createVehicle(any(Vehicle.class))).thenReturn(vehicle);

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vehicle)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.licensePlate").value("5678-XYZ"));
    }

    @Test
    void testGetInspectionsByStatus() throws Exception {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setStatus(VehicleInspection.InspectionStatus.APPROVED);

        when(inspectionService.getInspectionsByStatus(VehicleInspection.InspectionStatus.APPROVED))
                .thenReturn(List.of(inspection));

        mockMvc.perform(get("/api/v1/fleet/inspections/filter")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
    }

    @Test
    void testUpdateKilometersVehicleNotFound() throws Exception {
        when(vehicleService.updateVehicleKilometers(eq(99L), anyInt()))
                .thenThrow(new ResourceNotFoundException("Vehículo no encontrado con ID: 99"));

        mockMvc.perform(patch("/api/v1/fleet/vehicles/99/kilometers")
                        .param("kilometers", "1000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    @Test
    void testRegisterVehicleDuplicateLicensePlate() throws Exception {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate("5678-XYZ");
        vehicle.setCurrentKilometers(1000);

        when(vehicleService.createVehicle(any(Vehicle.class)))
                .thenThrow(new DuplicateResourceException("Ya existe un vehículo con la matrícula: 5678-XYZ"));

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vehicle)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }
}
