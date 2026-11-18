package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.dto.VehicleRequest;
import com.company.nopar.dto.VehicleResponse;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.InspectionService;
import com.company.nopar.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Flota", description = "Vehículos, sus partes y su devolución")
@RestController
@RequestMapping("/api/v1/fleet")
public class FleetController {

    private final VehicleService vehicleService;
    private final InspectionService inspectionService;

    public FleetController(VehicleService vehicleService, InspectionService inspectionService) {
        this.vehicleService = vehicleService;
        this.inspectionService = inspectionService;
    }

    // --- GESTIÓN DE VEHÍCULOS ---

    @Operation(summary = "Lista los vehículos", description = "Paginado. Conductores y administradores.")
    @GetMapping("/vehicles")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<PageResponse<VehicleResponse>> getAllVehicles(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.of(vehicleService.getAllVehicles(page, size), VehicleResponse::from));
    }

    @Operation(summary = "Consulta un vehículo")
    @ApiResponse(responseCode = "404", description = "El vehículo no existe")
    @GetMapping("/vehicles/{id}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<VehicleResponse> getVehicleById(@PathVariable Long id) {
        return vehicleService.getVehicleById(id)
                .map(VehicleResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Da de alta un vehículo", description = "Solo ADMIN.")
    @ApiResponse(responseCode = "409", description = "Ya existe un vehículo con esa matrícula")
    @PostMapping("/vehicles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VehicleResponse> registerVehicle(@Valid @RequestBody VehicleRequest request) {
        Vehicle savedVehicle = vehicleService.createVehicle(request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED).body(VehicleResponse.from(savedVehicle));
    }

    @Operation(summary = "Corrige los kilómetros de un vehículo",
            description = "Solo ADMIN. Acepta cualquier valor mayor o igual que 0 (los partes de los conductores nunca pueden bajarlos).")
    @PatchMapping("/vehicles/{id}/kilometers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VehicleResponse> updateKilometers(
            @PathVariable Long id,
            @RequestParam @Min(value = 0, message = "Los kilómetros no pueden ser negativos") int kilometers) {
        Vehicle updated = vehicleService.updateVehicleKilometers(id, kilometers);
        return ResponseEntity.ok(VehicleResponse.from(updated));
    }

    @Operation(summary = "Elimina un vehículo", description = "Solo ADMIN.")
    @ApiResponse(responseCode = "409", description = "El vehículo tiene partes registrados y no se puede eliminar")
    @DeleteMapping("/vehicles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        vehicleService.deleteVehicle(id);
        return ResponseEntity.noContent().build();
    }

    // --- GESTIÓN DE INSPECCIONES ASOCIADAS A LA FLOTA ---

    @Operation(summary = "Lista los partes de un vehículo", description = "Paginado, del más reciente al más antiguo.")
    @GetMapping("/vehicles/{vehicleId}/inspections")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<PageResponse<InspectionResponse>> getInspectionsByVehicle(
            @PathVariable Long vehicleId,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.of(
                inspectionService.getInspectionsByVehicle(vehicleId, page, size), InspectionResponse::from));
    }

    @Operation(summary = "Filtra los partes por estado",
            description = "Solo ADMIN. Por ejemplo SUBMITTED para ver los pendientes de revisión.")
    @GetMapping("/inspections/filter")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<InspectionResponse>> getInspectionsByStatus(
            @RequestParam VehicleInspection.InspectionStatus status,
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.of(
                inspectionService.getInspectionsByStatus(status, page, size), InspectionResponse::from));
    }
}