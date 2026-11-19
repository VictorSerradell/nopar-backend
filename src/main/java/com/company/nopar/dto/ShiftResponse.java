package com.company.nopar.dto;

import com.company.nopar.entity.ShiftAssignment;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Vista pública de un turno. No expone la entidad User (hash de contraseña, roles). */
public record ShiftResponse(
        Long id,
        LocalDate workDate,
        Long waveId,
        String waveName,
        LocalTime waveStartTime,
        Long driverId,
        String driverUsername,
        Long vehicleId,
        String vehicleLicensePlate
) {

    public static ShiftResponse from(ShiftAssignment shift) {
        var wave = shift.getWave();
        var driver = shift.getDriver();
        var vehicle = shift.getVehicle();
        return new ShiftResponse(
                shift.getId(),
                shift.getWorkDate(),
                wave != null ? wave.getId() : null,
                wave != null ? wave.getName() : null,
                wave != null ? wave.getStartTime() : null,
                driver != null ? driver.getId() : null,
                driver != null ? driver.getUsername() : null,
                vehicle != null ? vehicle.getId() : null,
                vehicle != null ? vehicle.getLicensePlate() : null
        );
    }

    public static List<ShiftResponse> fromAll(List<ShiftAssignment> shifts) {
        return shifts.stream().map(ShiftResponse::from).toList();
    }
}
