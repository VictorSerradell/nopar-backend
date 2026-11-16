package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.VehicleInspectionService;
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
    public ResponseEntity<VehicleInspection> registerInspection(
            @PathVariable Long vehicleId,
            @AuthenticationPrincipal User currentUser,
            @RequestBody VehicleInspection inspectionRequest) {

        VehicleInspection savedInspection = inspectionService.registerInspection(vehicleId, currentUser, inspectionRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedInspection);
    }

    @GetMapping("/vehicle/{vehicleId}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<List<VehicleInspection>> getInspectionsByVehicle(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(inspectionService.getInspectionsByVehicle(vehicleId));
    }

    @GetMapping("/driver/me")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<List<VehicleInspection>> getMyInspections(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(inspectionService.getInspectionsByDriver(currentUser.getId()));
    }
}