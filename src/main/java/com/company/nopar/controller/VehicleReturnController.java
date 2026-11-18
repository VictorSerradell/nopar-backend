package com.company.nopar.controller;

import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.dto.VehicleReturnResponse;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.service.VehicleReturnService;
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

@RestController
@RequestMapping("/api/v1/fleet/vehicles")
@RequiredArgsConstructor
public class VehicleReturnController {

    private final VehicleReturnService vehicleReturnService;

    /**
     * Devolución del vehículo al acabar el turno. Solo quien hizo el último parte, o un administrador.
     * Con daños el vehículo pasa a MAINTENANCE; sin ellos, a AVAILABLE.
     */
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
