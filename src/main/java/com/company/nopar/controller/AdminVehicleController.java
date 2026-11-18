package com.company.nopar.controller;

import com.company.nopar.dto.VehicleResponse;
import com.company.nopar.dto.VehicleStatusRequest;
import com.company.nopar.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/vehicles")
@RequiredArgsConstructor
public class AdminVehicleController {

    private final VehicleService vehicleService;

    /** Cambia el estado de un vehículo, p. ej. de MAINTENANCE a AVAILABLE cuando ya está reparado. */
    @PatchMapping("/{id}/status")
    public ResponseEntity<VehicleResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody VehicleStatusRequest request
    ) {
        return ResponseEntity.ok(VehicleResponse.from(
                vehicleService.changeVehicleStatus(id, request.toVehicleStatus())));
    }
}
