package com.company.nopar.service;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InspectionService {

    private final VehicleInspectionRepository inspectionRepository;
    private final VehicleRepository vehicleRepository;
    private final FileStorageService fileStorageService;

    @Transactional
    public VehicleInspection createInspection(
            InspectionCreateRequest request,
            MultipartFile odometerPhoto,
            MultipartFile beaconPhoto,
            MultipartFile exteriorPhoto,
            Long userId
    ) {
        // 1. Verificar que el vehículo existe
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new IllegalArgumentException("Vehículo no encontrado con ID: " + request.vehicleId()));

        // 2. Guardar archivos adjuntos de forma segura
        String odometerPath = fileStorageService.storeFile(odometerPhoto);
        String beaconPath = fileStorageService.storeFile(beaconPhoto);
        String exteriorPath = fileStorageService.storeFile(exteriorPhoto);

        // 3. Actualizar los kilómetros actuales del vehículo
        vehicle.setCurrentKilometers(request.kilometers());
        vehicle.setStatus(Vehicle.VehicleStatus.IN_USE);
        vehicleRepository.save(vehicle);

        // 4. Crear y guardar la inspección (parte matutino)
        VehicleInspection inspection = new VehicleInspection();
        inspection.setVehicle(vehicle);
        inspection.setUserId(userId);
        inspection.setKilometers(request.kilometers());
        inspection.setOdometerPhotoPath(odometerPath);
        inspection.setBeaconPhotoPath(beaconPath);
        inspection.setExteriorPhotosPath(exteriorPath);
        inspection.setDamagesReported(request.damagesReported());
        inspection.setAcceptedExclusiveUse(request.acceptedExclusiveUse());
        inspection.setAcceptedLiability(request.acceptedLiability());
        inspection.setAcceptedCleanliness(request.acceptedCleanliness());
        inspection.setStatus(VehicleInspection.InspectionStatus.SUBMITTED);

        return inspectionRepository.save(inspection);
    }
    public List<VehicleInspection> getAllInspections() {
        return inspectionRepository.findAll();
    }
}