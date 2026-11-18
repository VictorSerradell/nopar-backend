package com.company.nopar.controller;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.InspectionService;
import com.company.nopar.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

@WebMvcTest(FleetController.class)
@AutoConfigureMockMvc(addFilters = false)
class FleetControllerTest {

    private static final String VALID_BODY = """
            {"licensePlate": "5678-XYZ", "model": "Ford Transit", "hasBeacon": true, "currentKilometers": 1000}
            """;

    private static final String BODY_WITH_CLIENT_CONTROLLED_FIELDS = """
            {"id": 99, "status": "DAMAGED", "licensePlate": "5678-XYZ",
             "model": "Ford Transit", "currentKilometers": 1000}
            """;

    private static final String INVALID_BODY = """
            {"licensePlate": "  ", "currentKilometers": -5}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleService vehicleService;

    @MockitoBean
    private InspectionService inspectionService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void testGetAllVehicles() throws Exception {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(1L);
        vehicle.setLicensePlate("1234-ABC");
        vehicle.setCurrentKilometers(15000);

        when(vehicleService.getAllVehicles(0, 20))
                .thenReturn(new PageImpl<>(List.of(vehicle), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/fleet/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].licensePlate").value("1234-ABC"))
                .andExpect(jsonPath("$.content[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void givenPageAndSize_whenGetAllVehicles_thenForwardsThemAndReturnsPagingMetadata() throws Exception {
        when(vehicleService.getAllVehicles(2, 50))
                .thenReturn(new PageImpl<>(List.<Vehicle>of(), PageRequest.of(2, 50), 101));

        mockMvc.perform(get("/api/v1/fleet/vehicles").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(101))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void givenSizeAboveTheLimit_whenGetAllVehicles_thenBadRequestWithErrors() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors").isNotEmpty());

        verifyNoInteractions(vehicleService);
    }

    @Test
    void givenNegativePageOrZeroSize_whenGetAllVehicles_thenBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/fleet/vehicles").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/fleet/vehicles").param("size", "0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(vehicleService);
    }

    @Test
    void testGetVehicleById() throws Exception {
        when(vehicleService.getVehicleById(10L)).thenReturn(Optional.of(savedVehicle()));

        mockMvc.perform(get("/api/v1/fleet/vehicles/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.model").value("Ford Transit"))
                .andExpect(jsonPath("$.currentKilometers").value(1000));
    }

    @Test
    void testGetVehicleByIdNotFound() throws Exception {
        when(vehicleService.getVehicleById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/fleet/vehicles/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testRegisterVehicleSuccess() throws Exception {
        when(vehicleService.createVehicle(any(Vehicle.class))).thenReturn(savedVehicle());

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.licensePlate").value("5678-XYZ"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void givenBodyWithClientControlledFields_whenRegisterVehicle_thenTheyAreIgnored() throws Exception {
        when(vehicleService.createVehicle(any(Vehicle.class))).thenReturn(savedVehicle());

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_WITH_CLIENT_CONTROLLED_FIELDS))
                .andExpect(status().isCreated());

        ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleService).createVehicle(captor.capture());
        assertThat(captor.getValue().getId()).isNull();   // un id del cliente haria merge sobre otro vehiculo
        assertThat(captor.getValue().getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(captor.getValue().getLicensePlate()).isEqualTo("5678-XYZ");
    }

    @Test
    void givenInvalidBody_whenRegisterVehicle_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.licensePlate").value("La matrícula es obligatoria"))
                .andExpect(jsonPath("$.errors.model").value("El modelo es obligatorio"))
                .andExpect(jsonPath("$.errors.currentKilometers").value("Los kilómetros no pueden ser negativos"));

        verifyNoInteractions(vehicleService);
    }

    @Test
    void testRegisterVehicleDuplicateLicensePlate() throws Exception {
        when(vehicleService.createVehicle(any(Vehicle.class)))
                .thenThrow(new DuplicateResourceException("Ya existe un vehículo con la matrícula: 5678-XYZ"));

        mockMvc.perform(post("/api/v1/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void testUpdateKilometers() throws Exception {
        Vehicle updated = savedVehicle();
        updated.setCurrentKilometers(20000);
        when(vehicleService.updateVehicleKilometers(10L, 20000)).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/fleet/vehicles/10/kilometers")
                        .param("kilometers", "20000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.currentKilometers").value(20000));
    }

    @Test
    void givenNegativeKilometers_whenUpdateKilometers_thenBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/fleet/vehicles/10/kilometers")
                        .param("kilometers", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"));

        verifyNoInteractions(vehicleService);
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
    void testDeleteVehicle() throws Exception {
        mockMvc.perform(delete("/api/v1/fleet/vehicles/10"))
                .andExpect(status().isNoContent());

        verify(vehicleService).deleteVehicle(10L);
    }

    @Test
    void givenVehicleWithInspections_whenDelete_thenConflict() throws Exception {
        doThrow(new ResourceInUseException("No se puede eliminar el vehículo 10 porque tiene inspecciones registradas"))
                .when(vehicleService).deleteVehicle(10L);

        mockMvc.perform(delete("/api/v1/fleet/vehicles/10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Recurso en uso"));
    }

    @Test
    void testGetInspectionsByStatus() throws Exception {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setStatus(VehicleInspection.InspectionStatus.APPROVED);

        when(inspectionService.getInspectionsByStatus(VehicleInspection.InspectionStatus.APPROVED, 0, 20))
                .thenReturn(new PageImpl<>(List.of(inspection), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/fleet/inspections/filter")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"));
    }

    @Test
    void givenVehicle_whenGetInspectionsByVehicle_thenPageOfInspections() throws Exception {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setStatus(VehicleInspection.InspectionStatus.SUBMITTED);

        when(inspectionService.getInspectionsByVehicle(10L, 1, 5))
                .thenReturn(new PageImpl<>(List.of(inspection), PageRequest.of(1, 5), 6));

        mockMvc.perform(get("/api/v1/fleet/vehicles/10/inspections").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    private static Vehicle savedVehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setLicensePlate("5678-XYZ");
        vehicle.setModel("Ford Transit");
        vehicle.setHasBeacon(true);
        vehicle.setCurrentKilometers(1000);
        return vehicle;
    }
}
