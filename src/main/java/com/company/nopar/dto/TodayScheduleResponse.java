package com.company.nopar.dto;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * La planificación de hoy tal como la necesita quien la monta: cada ola con sus conductores y, aparte, los
 * conductores y las furgonetas que aún no tienen turno hoy (los que se pueden añadir a una ola). Las furgonetas en
 * mantenimiento o dañadas no salen entre las libres porque no se pueden asignar. No expone la entidad User.
 */
public record TodayScheduleResponse(
        LocalDate date,
        List<WaveShifts> waves,
        List<DriverRef> freeDrivers,
        List<VehicleRef> freeVehicles
) {

    /** Una ola con los turnos de hoy, por nombre de conductor. */
    public record WaveShifts(Long id, String name, LocalTime startTime, List<ShiftEntry> shifts) {
    }

    public record ShiftEntry(Long id, Long driverId, String driverUsername, Long vehicleId, String vehicleLicensePlate) {
    }

    public record DriverRef(Long id, String username) {
    }

    public record VehicleRef(Long id, String licensePlate, String model) {
    }

    public static TodayScheduleResponse from(LocalDate date, List<Wave> waves, List<ShiftAssignment> shifts,
                                             List<User> freeDrivers, List<Vehicle> freeVehicles) {
        List<WaveShifts> waveViews = waves.stream()
                .map(wave -> new WaveShifts(wave.getId(), wave.getName(), wave.getStartTime(), shifts.stream()
                        .filter(shift -> shift.getWave().getId().equals(wave.getId()))
                        .map(shift -> new ShiftEntry(
                                shift.getId(),
                                shift.getDriver().getId(),
                                shift.getDriver().getUsername(),
                                shift.getVehicle().getId(),
                                shift.getVehicle().getLicensePlate()))
                        .sorted(Comparator.comparing(ShiftEntry::driverUsername, String.CASE_INSENSITIVE_ORDER))
                        .toList()))
                .toList();
        return new TodayScheduleResponse(
                date,
                waveViews,
                freeDrivers.stream().map(driver -> new DriverRef(driver.getId(), driver.getUsername())).toList(),
                freeVehicles.stream()
                        .map(vehicle -> new VehicleRef(vehicle.getId(), vehicle.getLicensePlate(), vehicle.getModel()))
                        .toList());
    }
}
