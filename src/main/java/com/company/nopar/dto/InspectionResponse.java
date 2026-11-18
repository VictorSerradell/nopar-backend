package com.company.nopar.dto;

import com.company.nopar.entity.VehicleInspection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista pública de una inspección. No expone la entidad User (hash de contraseña, roles)
 * ni las rutas de las fotos en el servidor.
 */
public record InspectionResponse(
        Long id,
        Long vehicleId,
        String vehicleLicensePlate,
        Long driverId,
        String driverUsername,
        LocalDateTime inspectionDate,
        Integer kilometers,
        Boolean tiresOk,
        Boolean lightsOk,
        Boolean beaconV16Present,
        String damagesReported,
        Boolean acceptedExclusiveUse,
        Boolean acceptedLiability,
        Boolean acceptedCleanliness,
        VehicleInspection.InspectionStatus status,
        String reviewedByUsername,
        LocalDateTime reviewedAt
) {

    public static InspectionResponse from(VehicleInspection inspection) {
        var vehicle = inspection.getVehicle();
        var driver = inspection.getDriver();
        var reviewer = inspection.getReviewedBy();
        return new InspectionResponse(
                inspection.getId(),
                vehicle != null ? vehicle.getId() : null,
                vehicle != null ? vehicle.getLicensePlate() : null,
                driver != null ? driver.getId() : null,
                driver != null ? driver.getUsername() : null,
                inspection.getInspectionDate(),
                inspection.getKilometers(),
                inspection.getTiresOk(),
                inspection.getLightsOk(),
                inspection.getBeaconV16Present(),
                inspection.getDamagesReported(),
                inspection.getAcceptedExclusiveUse(),
                inspection.getAcceptedLiability(),
                inspection.getAcceptedCleanliness(),
                inspection.getStatus(),
                reviewer != null ? reviewer.getUsername() : null,
                inspection.getReviewedAt()
        );
    }

    public static List<InspectionResponse> fromAll(List<VehicleInspection> inspections) {
        return inspections.stream().map(InspectionResponse::from).toList();
    }
}
