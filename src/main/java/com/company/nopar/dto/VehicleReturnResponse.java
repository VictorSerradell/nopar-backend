package com.company.nopar.dto;

import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleReturn;

import java.time.LocalDateTime;

/** Vista pública de una devolución. No expone la entidad User (hash de contraseña, roles). */
public record VehicleReturnResponse(
        Long id,
        Long vehicleId,
        String vehicleLicensePlate,
        String returnedByUsername,
        LocalDateTime returnedAt,
        Integer kilometers,
        String damagesReported,
        Vehicle.VehicleStatus vehicleStatus
) {

    public static VehicleReturnResponse from(VehicleReturn vehicleReturn) {
        var vehicle = vehicleReturn.getVehicle();
        var returnedBy = vehicleReturn.getReturnedBy();
        return new VehicleReturnResponse(
                vehicleReturn.getId(),
                vehicle != null ? vehicle.getId() : null,
                vehicle != null ? vehicle.getLicensePlate() : null,
                returnedBy != null ? returnedBy.getUsername() : null,
                vehicleReturn.getReturnedAt(),
                vehicleReturn.getKilometers(),
                vehicleReturn.getDamagesReported(),
                vehicle != null ? vehicle.getStatus() : null
        );
    }
}
