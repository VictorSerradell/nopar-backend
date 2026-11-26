package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los partes de los vehículos, de solo lectura, para los encargados (y los administradores): ven el estado de la
 * flota y los daños que han reportado los conductores. Aprobar o rechazar un parte sigue siendo del administrador
 * (PATCH /api/v1/admin/inspections/{id}/review). SecurityConfig reserva /api/v1/dispatch a esos dos roles.
 */
@Tag(name = "Partes (encargados)", description = "Consulta de los partes de los vehículos para encargados y administradores")
@RestController
@RequestMapping("/api/v1/dispatch/inspections")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class DispatchInspectionController {

    private final InspectionService inspectionService;

    @Operation(summary = "Lista los partes",
            description = "Paginado, del más reciente al más antiguo. Con `status` (SUBMITTED, APPROVED o REJECTED) solo los de ese estado; sin él, todos. Las fotos de cada parte se descargan con GET /api/v1/inspections/{id}/photos/{odometer|beacon|exterior}, que también pueden ver los encargados. Revisar un parte (aprobar o rechazar) es del administrador.")
    @ApiResponse(responseCode = "400", description = "El estado no es SUBMITTED, APPROVED ni REJECTED, o la paginación no es válida")
    @GetMapping
    public ResponseEntity<PageResponse<InspectionResponse>> getInspections(
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
}
