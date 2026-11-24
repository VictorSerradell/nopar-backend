package com.company.nopar.dto;

import com.company.nopar.entity.InspectionPhotoType;
import com.company.nopar.entity.VehicleInspection;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista pública de una inspección. No expone la entidad User (hash de contraseña, roles)
 * ni las rutas de las fotos en el servidor.
 *
 * Las fotos se descargan aparte: cada campo *PhotoUrl es la ruta relativa de la API
 * ({@code /api/v1/inspections/{id}/photos/odometer|beacon|exterior}) o null si el parte no tiene esa foto.
 * Hay que pedirla con la cabecera Authorization, como cualquier otro endpoint.
 *
 * Enlace informativo con el cuadrante: {@code shiftId} es el turno que el conductor tenía ese día al hacer el parte
 * y {@code assignedVehicleMatches} indica si la furgoneta del parte era la asignada en él. Ambos son null si no
 * tenía turno (o si el turno se borró después). Nunca bloquean el parte.
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
        LocalDateTime reviewedAt,
        Long shiftId,
        Boolean assignedVehicleMatches,
        String odometerPhotoUrl,
        String beaconPhotoUrl,
        String exteriorPhotoUrl
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
                inspection.getReviewedAt(),
                inspection.getShiftId(),
                // sin turno (o con el turno ya borrado) no hay con qué comparar: no se muestra el dato suelto
                inspection.getShiftId() != null ? inspection.getMatchesAssignedVehicle() : null,
                photoUrl(inspection, InspectionPhotoType.ODOMETER),
                photoUrl(inspection, InspectionPhotoType.BEACON),
                photoUrl(inspection, InspectionPhotoType.EXTERIOR)
        );
    }

    private static String photoUrl(VehicleInspection inspection, InspectionPhotoType type) {
        boolean hasPhoto = inspection.getId() != null && type.storedNameOf(inspection) != null;
        return hasPhoto ? type.urlFor(inspection.getId()) : null;
    }

    public static List<InspectionResponse> fromAll(List<VehicleInspection> inspections) {
        return inspections.stream().map(InspectionResponse::from).toList();
    }
}
