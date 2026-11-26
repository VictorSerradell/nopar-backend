package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.InspectionReviewRequest;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. */
@Tag(name = "Administración", description = "Operaciones reservadas al rol ADMIN")
@RestController
@RequestMapping("/api/v1/admin/inspections")
@RequiredArgsConstructor
public class AdminInspectionController {

    private final InspectionService inspectionService;

    @Operation(summary = "Lista todos los partes",
            description = "Paginado, del más reciente al más antiguo. Con `status` (SUBMITTED, APPROVED o REJECTED) solo los de ese estado; sin él, todos.")
    @ApiResponse(responseCode = "400", description = "El estado no es SUBMITTED, APPROVED ni REJECTED, o la paginación no es válida")
    @GetMapping
    public ResponseEntity<PageResponse<InspectionResponse>> getAllInspections(
            @RequestParam(required = false) VehicleInspection.InspectionStatus status,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        Page<VehicleInspection> inspections = status != null
                ? inspectionService.getInspectionsByStatus(status, page, size)
                : inspectionService.getAllInspections(page, size);
        return ResponseEntity.ok(PageResponse.of(inspections, InspectionResponse::from));
    }

    /** Aprueba o rechaza una inspección pendiente (SUBMITTED). Queda constancia de quién y cuándo. */
    @Operation(summary = "Aprueba o rechaza un parte pendiente",
            description = "Solo partes en SUBMITTED. Queda constancia de quién y cuándo. Si se rechaza, el vehículo pasa a MAINTENANCE.")
    @ApiResponse(responseCode = "404", description = "El parte no existe")
    @ApiResponse(responseCode = "409", description = "El parte ya fue revisado")
    @PatchMapping("/{id}/review")
    public ResponseEntity<InspectionResponse> reviewInspection(
            @PathVariable Long id,
            @Valid @RequestBody InspectionReviewRequest request,
            @AuthenticationPrincipal User currentAdmin
    ) {
        VehicleInspection reviewed = inspectionService.reviewInspection(id, request.decision(), currentAdmin);
        return ResponseEntity.ok(InspectionResponse.from(reviewed));
    }
}
