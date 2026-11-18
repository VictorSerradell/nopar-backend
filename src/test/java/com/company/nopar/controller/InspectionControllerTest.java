package com.company.nopar.controller;

import com.company.nopar.entity.InspectionPhotoType;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.FileStorageService;
import com.company.nopar.service.InspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InspectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class InspectionControllerTest {

    private static final String VALID_DATA = """
            {"vehicleId": 5, "kilometers": 15000,
             "tiresOk": true, "lightsOk": true, "beaconV16Present": true,
             "damagesReported": "Sin daños",
             "acceptedExclusiveUse": true, "acceptedLiability": true, "acceptedCleanliness": true}
            """;

    private static final String DATA_WITHOUT_LIABILITY = """
            {"vehicleId": 5, "kilometers": 15000,
             "tiresOk": true, "lightsOk": true, "beaconV16Present": true,
             "acceptedExclusiveUse": true, "acceptedLiability": false, "acceptedCleanliness": true}
            """;

    private static final String DATA_WITHOUT_CHECKLIST = """
            {"vehicleId": 5, "kilometers": 15000,
             "acceptedExclusiveUse": true, "acceptedLiability": true, "acceptedCleanliness": true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InspectionService inspectionService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenValidRequest_whenCreateInspection_thenCreatedWithoutServerPathsNorDriverEntity() throws Exception {
        when(inspectionService.createInspection(any(), any(), any(), any(), any())).thenReturn(savedInspection());

        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(VALID_DATA))
                        .file(odometerPhoto()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1234-ABC"))
                .andExpect(jsonPath("$.driverUsername").value("conductor1"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.tiresOk").value(true))
                .andExpect(jsonPath("$.driver").doesNotExist())
                .andExpect(jsonPath("$.odometerPhotoPath").doesNotExist())
                .andExpect(jsonPath("$.odometerPhotoUrl").value("/api/v1/inspections/1/photos/odometer"))
                .andExpect(jsonPath("$.beaconPhotoUrl").doesNotExist())
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenRejectedPhoto_whenCreateInspection_thenBadRequestWithProblemDetail() throws Exception {
        when(inspectionService.createInspection(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidFileException("Formato de archivo no permitido. Solo se aceptan imágenes JPEG y PNG."));

        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(VALID_DATA))
                        .file(odometerPhoto()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Archivo no válido"))
                .andExpect(jsonPath("$.detail").value("Formato de archivo no permitido. Solo se aceptan imágenes JPEG y PNG."));
    }

    @Test
    void givenUnknownVehicle_whenCreateInspection_thenNotFound() throws Exception {
        when(inspectionService.createInspection(any(), any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Vehículo no encontrado con ID: 5"));

        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(VALID_DATA))
                        .file(odometerPhoto()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    @Test
    void givenKilometersBelowTheRegisteredOnes_whenCreateInspection_thenConflict() throws Exception {
        String detail = "Los kilómetros indicados (14999) son menores que los registrados para el vehículo (15000)";
        when(inspectionService.createInspection(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidKilometersException(detail));

        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(VALID_DATA))
                        .file(odometerPhoto()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Kilómetros no válidos"))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    @Test
    void givenVehicleInMaintenance_whenCreateInspection_thenConflict() throws Exception {
        String detail = "El vehículo 1234-ABC está en mantenimiento y no admite partes hasta que un administrador cambie su estado";
        when(inspectionService.createInspection(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidStateException(detail));

        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(VALID_DATA))
                        .file(odometerPhoto()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    @Test
    void givenLiabilityNotAccepted_whenCreateInspection_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(DATA_WITHOUT_LIABILITY))
                        .file(odometerPhoto()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.acceptedLiability").exists());

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenMissingChecklist_whenCreateInspection_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(multipart("/api/v1/inspections")
                        .file(data(DATA_WITHOUT_CHECKLIST))
                        .file(odometerPhoto()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tiresOk").exists())
                .andExpect(jsonPath("$.errors.lightsOk").exists())
                .andExpect(jsonPath("$.errors.beaconV16Present").exists());

        verifyNoInteractions(inspectionService);
    }

    // ------------------------------------------------------------ fotos del parte

    @Test
    void givenStoredPhoto_whenGetPhoto_thenImageBytesWithItsTypeAndPrivateCache() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        when(inspectionService.getPhoto(eq(1L), eq(InspectionPhotoType.ODOMETER), any()))
                .thenReturn(new FileStorageService.StoredFile(new ByteArrayResource(jpeg), MediaType.IMAGE_JPEG));

        mockMvc.perform(get("/api/v1/inspections/1/photos/odometer"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg))
                .andExpect(header().string("Cache-Control", "max-age=3600, private"));
    }

    @Test
    void givenUnknownPhotoType_whenGetPhoto_thenNotFoundWithoutTouchingTheService() throws Exception {
        mockMvc.perform(get("/api/v1/inspections/1/photos/passport"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenMissingPhoto_whenGetPhoto_thenNotFoundWithProblemDetail() throws Exception {
        when(inspectionService.getPhoto(eq(1L), eq(InspectionPhotoType.BEACON), any()))
                .thenThrow(new ResourceNotFoundException("La inspección 1 no tiene foto de tipo beacon"));

        mockMvc.perform(get("/api/v1/inspections/1/photos/beacon"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"))
                .andExpect(jsonPath("$.detail").value("La inspección 1 no tiene foto de tipo beacon"));
    }

    private static MockMultipartFile data(String json) {
        return new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile odometerPhoto() {
        return new MockMultipartFile("odometerPhoto", "odo.jpg", "image/jpeg", new byte[]{1, 2, 3});
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
        inspection.setOdometerPhotoPath("odo.jpg");
        inspection.setStatus(VehicleInspection.InspectionStatus.APPROVED);
        return inspection;
    }
}
