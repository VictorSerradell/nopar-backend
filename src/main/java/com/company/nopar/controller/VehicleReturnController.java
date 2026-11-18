package com.company.nopar.controller;

import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.dto.VehicleReturnResponse;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.service.VehicleReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flota", description = "Vehículos, sus partes y su devolución")
@RestController
@RequestMapping("/api/v1/fleet/vehicles")
@RequiredArgsConstructor
public class VehicleReturnController {

    private final VehicleReturnService vehicleReturnService;

    /**
     * Devolución del vehículo al acabar el turno. Solo quien hizo el último parte, o un administrador.
     * Con daños el vehículo pasa a MAINTENANCE; sin ellos, a AVAILABLE.
     */
    @Operation(summary = "Devuelve el vehículo al acabar el turno",
            description = "Solo quien hizo el último parte del vehículo, o un administrador. Sin daños el vehículo pasa a AVAILABLE; con daños, a MAINTENANCE. Los kilómetros no pueden ser menores que los registrados.")
    @ApiResponse(responseCode = "403", description = "El último parte del vehículo lo hizo otro conductor")
    @ApiResponse(responseCode = "404", description = "El vehículo no existe")
    @ApiResponse(responseCode = "409", description = "El vehículo no está en uso, o los kilómetros son menores que los registrados")
    @PostMapping("/{vehicleId}/return")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<VehicleReturnResponse> returnVehicle(
            @PathVariable Long vehicleId,
            @Valid @RequestBody VehicleReturnRequest request,
            @AuthenticationPrincipal User currentUser // quien devuelve sale del token JWT, no lo envía el cliente
    ) {
        VehicleReturn saved = vehicleReturnService.returnVehicle(vehicleId, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(VehicleReturnResponse.from(saved));
    }
}
