package com.company.nopar.controller;

import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.service.VehicleInspectionService;
import com.company.nopar.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fleet")
public class FleetController {

    private final VehicleService vehicleService;
    private final VehicleInspectionService inspectionService;

    public FleetController(VehicleService vehicleService, VehicleInspectionService inspectionService) {
        this.vehicleService = vehicleService;
        this.inspectionService = inspectionService;
    }

    // --- GESTIÓN DE VEHÍCULOS ---

    @GetMapping("/vehicles")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<List<Vehicle>> getAllVehicles() {
        return ResponseEntity.ok(vehicleService.getAllVehicles());
    }

    @GetMapping("/vehicles/{id}")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<Vehicle> getVehicleById(@PathVariable Long id) {
        return vehicleService.getVehicleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/vehicles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Vehicle> registerVehicle(@Valid @RequestBody Vehicle vehicle) {
        Vehicle savedVehicle = vehicleService.createVehicle(vehicle);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedVehicle);
    }

    @PatchMapping("/vehicles/{id}/kilometers")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<Vehicle> updateKilometers(@PathVariable Long id, @RequestParam int kilometers) {
        Vehicle updated = vehicleService.updateVehicleKilometers(id, kilometers);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/vehicles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        vehicleService.deleteVehicle(id);
        return ResponseEntity.noContent().build();
    }

    // --- GESTIÓN DE INSPECCIONES ASOCIADAS A LA FLOTA ---

    @GetMapping("/vehicles/{vehicleId}/inspections")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<List<InspectionResponse>> getInspectionsByVehicle(@PathVariable Long vehicleId) {
        List<VehicleInspection> inspections = inspectionService.getInspectionsByVehicle(vehicleId);
        return ResponseEntity.ok(InspectionResponse.fromAll(inspections));
    }

    @GetMapping("/inspections/filter")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<InspectionResponse>> getInspectionsByStatus(
            @RequestParam VehicleInspection.InspectionStatus status) {
        List<VehicleInspection> inspections = inspectionService.getInspectionsByStatus(status);
        return ResponseEntity.ok(InspectionResponse.fromAll(inspections));
    }
}