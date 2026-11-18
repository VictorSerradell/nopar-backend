package com.company.nopar.controller;

import com.company.nopar.dto.InspectionReviewRequest.Decision;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.security.JwtService;
import com.company.nopar.service.InspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminInspectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminInspectionControllerTest {

    private static final String APPROVE = """
            {"decision": "APPROVED"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InspectionService inspectionService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void givenInspections_whenGetAll_thenReturnsPublicView() throws Exception {
        when(inspectionService.getAllInspections(0, 20))
                .thenReturn(new PageImpl<>(
                        List.of(inspection(VehicleInspection.InspectionStatus.SUBMITTED)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/inspections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.content[0].reviewedByUsername").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenPendingInspection_whenApprove_thenOkWithReviewerAndDate() throws Exception {
        VehicleInspection reviewed = inspection(VehicleInspection.InspectionStatus.APPROVED);
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPassword("$2a$10$hash-que-no-debe-salir");
        admin.setRole(User.Role.ADMIN);
        reviewed.setReviewedBy(admin);
        reviewed.setReviewedAt(LocalDateTime.of(2026, 11, 18, 9, 30));
        when(inspectionService.reviewInspection(eq(1L), eq(Decision.APPROVED), any())).thenReturn(reviewed);

        mockMvc.perform(patch("/api/v1/admin/inspections/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByUsername").value("admin"))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenSubmittedAsDecision_whenReview_thenBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/inspections/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "SUBMITTED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Cuerpo no válido"));

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenMissingDecision_whenReview_thenBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/inspections/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors.decision").value("La decisión es obligatoria"));

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenAlreadyReviewedInspection_whenReview_thenConflict() throws Exception {
        when(inspectionService.reviewInspection(eq(1L), any(), any()))
                .thenThrow(new InvalidStateException("La inspección 1 ya fue revisada (estado: APPROVED)"));

        mockMvc.perform(patch("/api/v1/admin/inspections/1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"));
    }

    @Test
    void givenUnknownInspection_whenReview_thenNotFound() throws Exception {
        when(inspectionService.reviewInspection(eq(99L), any(), any()))
                .thenThrow(new ResourceNotFoundException("Inspección no encontrada con ID: 99"));

        mockMvc.perform(patch("/api/v1/admin/inspections/99/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPROVE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    private static VehicleInspection inspection(VehicleInspection.InspectionStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");

        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setVehicle(vehicle);
        inspection.setKilometers(15000);
        inspection.setStatus(status);
        return inspection;
    }
}
