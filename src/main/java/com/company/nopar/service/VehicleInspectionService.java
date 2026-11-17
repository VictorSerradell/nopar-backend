package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleInspectionService {

    private final VehicleInspectionRepository inspectionRepository;
    private final VehicleRepository vehicleRepository;

    @Transactional
    public VehicleInspection registerInspection(Long vehicleId, User driver, VehicleInspection inspectionRequest) {
        // 1. Verificamos que el vehículo exista
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new RuntimeException("Vehículo no encontrado con ID: " + vehicleId));

        // 2. Evaluamos reglas de negocio usando los getters correctos para Boolean
        boolean isApproved = Boolean.TRUE.equals(inspectionRequest.getTiresOk()) &&
                Boolean.TRUE.equals(inspectionRequest.getLightsOk()) &&
                Boolean.TRUE.equals(inspectionRequest.getBeaconV16Present());

        inspectionRequest.setVehicle(vehicle);
        inspectionRequest.setDriver(driver);
        inspectionRequest.setStatus(isApproved ?
                VehicleInspection.InspectionStatus.APPROVED :
                VehicleInspection.InspectionStatus.REJECTED);

        // 3. Actualizamos los kilómetros actuales del vehículo
        vehicle.setCurrentKilometers(inspectionRequest.getKilometers());
        vehicleRepository.save(vehicle);

        // 4. Guardamos y devolvemos la inspección
        return inspectionRepository.save(inspectionRequest);
    }

    public List<VehicleInspection> getInspectionsByVehicle(Long vehicleId) {
        return inspectionRepository.findByVehicleId(vehicleId);
    }

    public List<VehicleInspection> getInspectionsByDriver(Long driverId) {
        return inspectionRepository.findByDriverId(driverId);
    }
    public List<VehicleInspection> getInspectionsByStatus(VehicleInspection.InspectionStatus status) {
        return inspectionRepository.findByStatus(status);
    }
}