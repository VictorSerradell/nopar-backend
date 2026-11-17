package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.VehicleInspectionRequest;
import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.VehicleInspectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inspections")
@RequiredArgsConstructor
public class VehicleInspectionController {

    private final VehicleInspectionService inspectionService;

    @PostMapping("/vehicle/{vehicleId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<InspectionResponse> registerInspection(
            @PathVariable Long vehicleId,
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody VehicleInspectionRequest request) {

        VehicleInspection savedInspection =
                inspectionService.registerInspection(vehicleId, currentUser, request.toEntity());
        return ResponseEntity.status(HttpStatus.CREATED).body(InspectionResponse.from(savedInspection));
    }

    @GetMapping("/vehicle/{vehicleId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<List<InspectionResponse>> getInspectionsByVehicle(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(InspectionResponse.fromAll(inspectionService.getInspectionsByVehicle(vehicleId)));
    }

    @GetMapping("/driver/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<InspectionResponse>> getMyInspections(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(InspectionResponse.fromAll(inspectionService.getInspectionsByDriver(currentUser.getId())));
    }
}
