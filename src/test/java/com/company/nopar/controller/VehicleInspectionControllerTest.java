package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.VehicleInspectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleInspectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class VehicleInspectionControllerTest {

    private static final String VALID_BODY = """
            {"kilometers": 15000, "tiresOk": true, "lightsOk": true, "beaconV16Present": true}
            """;

    private static final String BODY_WITH_CLIENT_CONTROLLED_FIELDS = """
            {"id": 99, "status": "APPROVED", "kilometers": 15000,
             "tiresOk": true, "lightsOk": true, "beaconV16Present": true}
            """;

    private static final String BODY_WITHOUT_KILOMETERS = """
            {"tiresOk": true, "lightsOk": true, "beaconV16Present": true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleInspectionService inspectionService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenRegisterInspection_thenCreatedWithoutExposingTheDriverEntity() throws Exception {
        when(inspectionService.registerInspection(eq(5L), any(), any(VehicleInspection.class)))
                .thenReturn(savedInspection());

        mockMvc.perform(post("/api/v1/inspections/vehicle/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1234-ABC"))
                .andExpect(jsonPath("$.driverUsername").value("conductor1"))
                .andExpect(jsonPath("$.driver").doesNotExist())
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenBodyWithClientControlledFields_whenRegisterInspection_thenTheyAreIgnored() throws Exception {
        when(inspectionService.registerInspection(eq(5L), any(), any(VehicleInspection.class)))
                .thenReturn(savedInspection());

        mockMvc.perform(post("/api/v1/inspections/vehicle/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_WITH_CLIENT_CONTROLLED_FIELDS))
                .andExpect(status().isCreated());

        ArgumentCaptor<VehicleInspection> captor = ArgumentCaptor.forClass(VehicleInspection.class);
        verify(inspectionService).registerInspection(eq(5L), any(), captor.capture());
        assertThat(captor.getValue().getId()).isNull();      // un id del cliente haria merge sobre otra inspeccion
        assertThat(captor.getValue().getStatus()).isNull();  // el estado lo decide el servicio
        assertThat(captor.getValue().getKilometers()).isEqualTo(15000);
    }

    @Test
    void givenBodyWithoutKilometers_whenRegisterInspection_thenBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/inspections/vehicle/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_WITHOUT_KILOMETERS))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inspectionService);
    }

    private static VehicleInspection savedInspection() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");

        User driver = new User();
        driver.setId(7L);
        driver.setUsername("conductor1");
        driver.setPassword("$2a$10$hash-que-no-debe-salir");
        driver.setRole(User.Role.DRIVER);

        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setVehicle(vehicle);
        inspection.setDriver(driver);
        inspection.setKilometers(15000);
        inspection.setTiresOk(true);
        inspection.setLightsOk(true);
        inspection.setBeaconV16Present(true);
        inspection.setStatus(VehicleInspection.InspectionStatus.APPROVED);
        return inspection;
    }
}
